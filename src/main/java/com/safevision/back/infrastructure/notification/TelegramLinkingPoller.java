package com.safevision.back.infrastructure.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.safevision.back.application.service.SiteContactService;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vinculación de contactos por Telegram — short polling a {@code getUpdates}
 * de la Bot API (sin webhook público, ver CLAUDE.md). El offset se guarda en
 * memoria, no en BD: reprocesar un update viejo tras un reinicio es inofensivo
 * acá (el código ya fue consumido y no matchea nada, o vuelve a linkear sin
 * causar daño), así que no vale la pena persistirlo para esta fase.
 *
 * <p>Reemplaza "pegar el chat_id a mano" como flujo principal — ese camino
 * sigue existiendo como fallback (ver {@link SiteContactService#create}).
 */
@Component
public class TelegramLinkingPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkingPoller.class);
    private static final String TELEGRAM_API_BASE_URL = "https://api.telegram.org";
    // El supervisor manda "/start <codigo>" como un solo mensaje (deep-link
    // estandar de Telegram) — el "@nombre_bot" opcional aparece si Telegram
    // se lo agrega solo (pasa en chats grupales, no en privados, pero se
    // tolera igual). Si no matchea, se usa el texto tal cual (compatibilidad
    // con mandar el código solo, sin "/start").
    private static final Pattern START_COMMAND = Pattern.compile("(?i)^/start(?:@\\S+)?\\s+(\\S+)$");

    private final WebClient webClient;
    private final TelegramProperties properties;
    private final SiteContactService siteContactService;
    private final AtomicLong offset = new AtomicLong(0);

    @Autowired
    public TelegramLinkingPoller(WebClient.Builder webClientBuilder, TelegramProperties properties,
                                  SiteContactService siteContactService) {
        this(webClientBuilder, properties, siteContactService, TELEGRAM_API_BASE_URL);
    }

    TelegramLinkingPoller(WebClient.Builder webClientBuilder, TelegramProperties properties,
                           SiteContactService siteContactService, String apiBaseUrl) {
        this.webClient = webClientBuilder.baseUrl(apiBaseUrl).build();
        this.properties = properties;
        this.siteContactService = siteContactService;
    }

    @Scheduled(fixedDelayString = "${app.telegram.linking-poll-interval-ms:900000}")
    public void poll() {
        if (properties.botToken() == null || properties.botToken().isBlank()) {
            return;
        }
        fetchUpdates()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::handleUpdate)
                .onErrorResume(ex -> {
                    log.warn("Fallo el polling de Telegram (getUpdates) | {}", ex.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    private Mono<List<TelegramUpdate>> fetchUpdates() {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/bot{token}/getUpdates")
                        .queryParam("offset", offset.get())
                        .queryParam("timeout", 0)
                        .build(properties.botToken()))
                .retrieve()
                .bodyToMono(TelegramUpdatesResponse.class)
                .map(TelegramUpdatesResponse::result);
    }

    private Mono<Void> handleUpdate(TelegramUpdate update) {
        offset.set(update.updateId() + 1);
        if (update.message() == null || update.message().text() == null || update.message().chat() == null) {
            return Mono.empty();
        }
        String chatId = String.valueOf(update.message().chat().id());
        String code = extractLinkCode(update.message().text());
        // OJO: Mono<Void> siempre se considera "vacío" en términos de Reactive
        // Streams (nunca emite onNext) — si se encadenara switchIfEmpty()
        // justo después de un flatMap(...-> Mono<Void>), se dispararía SIEMPRE,
        // mandando doble mensaje incluso cuando el código matcheó. Por eso acá
        // se arma el texto de respuesta como Mono<String> (map, no flatMap) y
        // recién al final se manda un único sendMessage.
        return siteContactService.tryLinkByCode(code, chatId)
                .map(contactName -> "✅ Listo, quedaste vinculado a las alertas de SafeVision (" + contactName + ").")
                .switchIfEmpty(Mono.just(
                        "No reconozco ese código. Pídele a tu administrador el código de 6 dígitos de vinculación."))
                .flatMap(reply -> sendMessage(chatId, reply));
    }

    private String extractLinkCode(String text) {
        String trimmed = text.trim();
        Matcher matcher = START_COMMAND.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : trimmed;
    }

    private Mono<Void> sendMessage(String chatId, String text) {
        return webClient.post()
                .uri("/bot{token}/sendMessage", properties.botToken())
                .bodyValue(new TelegramSendMessageRequest(chatId, text))
                .retrieve()
                .toBodilessEntity()
                .then()
                .onErrorResume(ex -> {
                    log.warn("No se pudo responder por Telegram | chatId={} | {}", chatId, ex.getMessage());
                    return Mono.empty();
                });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramUpdatesResponse(boolean ok, List<TelegramUpdate> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramUpdate(@JsonProperty("update_id") long updateId, TelegramMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramMessage(TelegramChat chat, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramChat(long id) {}

    record TelegramSendMessageRequest(@JsonProperty("chat_id") String chatId, String text) {}
}
