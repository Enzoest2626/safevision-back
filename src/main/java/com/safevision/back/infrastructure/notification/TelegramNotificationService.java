package com.safevision.back.infrastructure.notification;

import com.safevision.back.application.ports.out.NotificationChannelPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * Adaptador de alertas — envía la evidencia del incidente EPP como foto
 * a un chat de Telegram vía la Bot API (sendPhoto).
 *
 * <p>Reintento básico (CP29): ante un error transitorio — 429 (rate-limit),
 * 5xx o falla de red — reintenta hasta {@link #MAX_RETRIES} veces con backoff
 * exponencial. Un 4xx distinto de 429 (ej. chat inválido) no se reintenta: el
 * resultado no cambiaría. Si se agotan los reintentos, el error se propaga y
 * {@code IncidentNotificationService} registra la notificación como FAILED.
 */
@Service
public class TelegramNotificationService implements NotificationChannelPort {

    private static final String TELEGRAM_API_BASE_URL = "https://api.telegram.org";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.forLanguageTag("es"));
    static final int MAX_RETRIES = 2;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);

    private final WebClient webClient;
    private final TelegramProperties properties;
    private final Duration retryBackoff;

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
        this(webClientBuilder, properties, apiBaseUrl, DEFAULT_RETRY_BACKOFF);
    }

    /** Permite a los tests usar un backoff de milisegundos en vez de segundos reales. */
    TelegramNotificationService(WebClient.Builder webClientBuilder, TelegramProperties properties,
                                String apiBaseUrl, Duration retryBackoff) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(apiBaseUrl).build();
        this.retryBackoff = retryBackoff;
    }

    @Override
    public Mono<Void> sendIncidentAlert(String chatId, Incident incident, Worker worker, Camera camera, Site site,
                                         String zoneName, String frameB64) {
        byte[] photoBytes = Base64.getDecoder().decode(frameB64);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", chatId);
        builder.part("parse_mode", "HTML");
        builder.part("caption", buildCaption(worker, camera, site, zoneName, incident));
        builder.part("photo", photoBytes).filename("evidence.jpg");

        return sendPhoto(builder);
    }

    /**
     * Igual que {@link #sendIncidentAlert}, pero para evidencia subida a S3 por
     * el módulo CV: en vez de bytes, se manda una URL prefirmada — la Bot API
     * de Telegram acepta un string URL en el campo {@code photo} y lo descarga
     * de su lado, sin que el backend tenga que bajar el objeto de S3.
     */
    @Override
    public Mono<Void> sendIncidentAlertByUrl(String chatId, Incident incident, Worker worker, Camera camera, Site site,
                                              String zoneName, String photoUrl) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", chatId);
        builder.part("parse_mode", "HTML");
        builder.part("caption", buildCaption(worker, camera, site, zoneName, incident));
        builder.part("photo", photoUrl);

        return sendPhoto(builder);
    }

    private Mono<Void> sendPhoto(MultipartBodyBuilder builder) {
        return deliver(webClient.post()
                .uri("/bot{token}/sendPhoto", properties.botToken())
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve());
    }

    /**
     * Mensaje de texto sin foto (Bot API {@code sendMessage}) — usado por el
     * reporte diario (HU12). Misma política de reintento que las fotos (CP29):
     * 429/5xx/falla de red se reintentan, otro 4xx no.
     */
    @Override
    public Mono<Void> sendTextMessage(String chatId, String text) {
        return deliver(webClient.post()
                .uri("/bot{token}/sendMessage", properties.botToken())
                .bodyValue(Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML"))
                .retrieve());
    }

    /** Mapea errores HTTP a {@link TelegramApiException} y aplica el reintento con backoff. */
    private Mono<Void> deliver(WebClient.ResponseSpec response) {
        return response
                .onStatus(HttpStatusCode::isError, spec -> spec.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new TelegramApiException(spec.statusCode(), body))))
                .toBodilessEntity()
                .then()
                .retryWhen(Retry.backoff(MAX_RETRIES, retryBackoff)
                        .filter(TelegramNotificationService::isTransient)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    /** 429, 5xx o falla de red: vale la pena reintentar. Otro 4xx: no. */
    private static boolean isTransient(Throwable error) {
        if (error instanceof TelegramApiException apiError) {
            return apiError.status.value() == 429 || apiError.status.is5xxServerError();
        }
        return error instanceof WebClientRequestException;
    }

    /** Error HTTP de la Bot API, con el status para decidir si se reintenta. */
    static final class TelegramApiException extends IllegalStateException {
        private final transient HttpStatusCode status;

        TelegramApiException(HttpStatusCode status, String body) {
            super("Telegram respondió " + status + ": " + body);
            this.status = status;
        }
    }

    /** Texto de la alerta: trabajador, EPP faltante, obra, zona, cámara, fecha y hora (CP28). */
    String buildCaption(Worker worker, Camera camera, Site site, String zoneName, Incident incident) {
        String eppList = String.join(", ", incident.missingEpp());
        String zoneLine = (zoneName != null && !zoneName.isBlank())
                ? "     Zona: " + escapeHtml(zoneName) + "\n"
                : "";
        return "⚠️ <b>Incumplimiento Detectado</b>\n\n" +
                "👷 Trabajador: " + escapeHtml(worker.firstName() + " " + worker.lastName())
                        + " (código " + worker.code() + ")\n" +
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
