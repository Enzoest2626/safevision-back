package com.safevision.back.service;

import com.safevision.back.config.TelegramProperties;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Site;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Adaptador de alertas — envía la evidencia del incidente EPP como foto
 * a un chat de Telegram vía la Bot API (sendPhoto).
 */
@Service
public class TelegramNotificationService {

    private static final String TELEGRAM_API_BASE_URL = "https://api.telegram.org";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.forLanguageTag("es"));

    private final WebClient webClient;
    private final TelegramProperties properties;

    /**
     * Recibe el {@link WebClient.Builder} (autoconfigurado por Spring Boot) en vez de
     * construir el WebClient internamente, para poder inyectar un ExchangeFunction
     * mockeado en tests unitarios sin llamadas HTTP reales.
     */
    @Autowired
    public TelegramNotificationService(WebClient.Builder webClientBuilder, TelegramProperties properties) {
        this(webClientBuilder, properties, TELEGRAM_API_BASE_URL);
    }

    /**
     * Constructor with an injectable base URL for deterministic HTTP integration tests.
     * Production wiring continues to use the Telegram Bot API base URL above.
     */
    TelegramNotificationService(WebClient.Builder webClientBuilder, TelegramProperties properties,
                                String apiBaseUrl) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(apiBaseUrl).build();
    }

    public Mono<Void> sendIncidentAlert(String chatId, Incident incident, Camera camera, Site site,
                                         String zoneName, String frameB64) {
        byte[] photoBytes = Base64.getDecoder().decode(frameB64);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", chatId);
        builder.part("parse_mode", "HTML");
        builder.part("caption", buildCaption(camera, site, zoneName, incident));
        builder.part("photo", photoBytes).filename("evidence.jpg");

        return webClient.post()
                .uri("/bot{token}/sendPhoto", properties.botToken())
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Telegram respondió " + response.statusCode() + ": " + body))))
                .toBodilessEntity()
                .then();
    }

    private String buildCaption(Camera camera, Site site, String zoneName, Incident incident) {
        String eppList = String.join(", ", incident.missingEpp());
        String zoneLine = (zoneName != null && !zoneName.isBlank())
                ? "     Zona: " + escapeHtml(zoneName) + "\n"
                : "";
        return "⚠️ <b>Incumplimiento Detectado</b>\n\n" +
                "🪖 EPP Faltante: " + escapeHtml(eppList) + "\n" +
                "📍 Obra: " + escapeHtml(site.name()) + "\n" +
                zoneLine +
                "📷 Cámara: " + escapeHtml(camera.name() != null ? camera.name() : camera.code()) + "\n" +
                "🕐 Fecha: " + incident.occurredAt().format(DATE_FORMAT) + "\n" +
                "     Hora: " + incident.occurredAt().format(TIME_FORMAT) + "\n\n" +
                "Por favor, verifique y tome las acciones correctivas necesarias.";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
