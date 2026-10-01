package com.safevision.back.infrastructure.webhook;

import com.safevision.back.application.ports.out.CameraConfigPublisherPort;
import com.safevision.back.domain.model.Camera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Notifica (best-effort, vía HTTP) la configuración vigente de una cámara al
 * módulo CV correspondiente — reemplaza el retained MQTT de antes. El CV
 * expone un pequeño servidor HTTP propio (webhook) en {@code camera.ipAddress}
 * más un puerto fijo; no hay broker de por medio.
 *
 * Fire-and-forget: un fallo (CV apagado, red caída) se loguea y no bloquea ni
 * propaga error al flujo que disparó la actualización — mismo criterio
 * best-effort que tenía el publisher MQTT.
 */
@Service
public class HttpCameraConfigPublisher implements CameraConfigPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCameraConfigPublisher.class);
    private static final String WEBHOOK_PATH = "/webhook/config";

    private final WebClient webClient;
    private final int webhookPort;
    private final Duration webhookTimeout;

    public HttpCameraConfigPublisher(WebClient.Builder webClientBuilder,
                                      @Value("${app.cv.webhook-port:5001}") int webhookPort,
                                      @Value("${app.cv.webhook-timeout-seconds:5}") long webhookTimeoutSeconds) {
        this.webClient = webClientBuilder.build();
        this.webhookPort = webhookPort;
        this.webhookTimeout = Duration.ofSeconds(webhookTimeoutSeconds);
    }

    @Override
    public void publishCameraConfig(Camera camera) {
        if (camera.ipAddress() == null || camera.ipAddress().isBlank()) {
            log.warn("Cámara {} sin ip_address — no se puede notificar su config al CV", camera.code());
            return;
        }
        URI uri = URI.create("http://" + camera.ipAddress() + ":" + webhookPort + WEBHOOK_PATH);
        Map<String, Object> payload = Map.of(
                "camera_code", camera.code(),
                "rtsp_url", camera.rtspUrl() != null ? camera.rtspUrl() : "",
                "active", camera.active()
        );

        webClient.post()
                .uri(uri)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(webhookTimeout)
                .doOnSuccess(response -> log.info(
                        "Config de cámara notificada al CV | uri={} | rtsp_url={} | active={}",
                        uri, camera.rtspUrl(), camera.active()))
                .onErrorResume(ex -> {
                    log.error("Fallo notificando config de cámara al CV | uri={} | {}", uri, ex.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }
}
