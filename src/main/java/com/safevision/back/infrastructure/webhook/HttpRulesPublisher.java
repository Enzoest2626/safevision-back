package com.safevision.back.infrastructure.webhook;

import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.RulesPublisherPort;
import com.safevision.back.domain.model.Camera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Notifica (best-effort, vía HTTP) las reglas EPP vigentes de una obra a
 * cada cámara activa de esa obra — reemplaza el retained MQTT de antes. Una
 * obra puede tener varias cámaras (varias instancias del CV), así que se
 * hace fan-out: un POST por cámara a su webhook.
 */
@Service
public class HttpRulesPublisher implements RulesPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(HttpRulesPublisher.class);
    private static final String WEBHOOK_PATH = "/webhook/rules";

    private final WebClient webClient;
    private final CameraRepositoryPort cameraRepository;
    private final int webhookPort;
    private final Duration webhookTimeout;

    public HttpRulesPublisher(WebClient.Builder webClientBuilder, CameraRepositoryPort cameraRepository,
                               @Value("${app.cv.webhook-port:5001}") int webhookPort,
                               @Value("${app.cv.webhook-timeout-seconds:5}") long webhookTimeoutSeconds) {
        this.webClient = webClientBuilder.build();
        this.cameraRepository = cameraRepository;
        this.webhookPort = webhookPort;
        this.webhookTimeout = Duration.ofSeconds(webhookTimeoutSeconds);
    }

    @Override
    public void publishRules(Long siteId, List<String> requiredEppCodes) {
        cameraRepository.findBySiteIdAndActiveTrue(siteId)
                .filter(camera -> camera.ipAddress() != null && !camera.ipAddress().isBlank())
                .flatMap(camera -> notifyCamera(camera, requiredEppCodes))
                .subscribe();
    }

    private Mono<Void> notifyCamera(Camera camera, List<String> requiredEppCodes) {
        URI uri = URI.create("http://" + camera.ipAddress() + ":" + webhookPort + WEBHOOK_PATH);
        return webClient.post()
                .uri(uri)
                .bodyValue(Map.of("required_epp", requiredEppCodes))
                .retrieve()
                .toBodilessEntity()
                .timeout(webhookTimeout)
                .doOnSuccess(response -> log.info(
                        "Reglas EPP notificadas al CV | uri={} | required_epp={}", uri, requiredEppCodes))
                .onErrorResume(ex -> {
                    log.error("Fallo notificando reglas EPP al CV | uri={} | {}", uri, ex.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
