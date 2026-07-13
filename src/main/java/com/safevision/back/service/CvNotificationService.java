package com.safevision.back.service;

import com.safevision.back.config.CvProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Avisa al módulo CV cuando cambian las reglas EPP de una obra (HU04),
 * para que recargue sin esperar su próximo ciclo de polling (60s).
 *
 * Best-effort y no bloqueante: si {@code CV_RELOAD_URL} no está configurada
 * o la llamada falla (CV reiniciando, red caída), no se reintenta ni se
 * propaga el error — el polling periódico del CV es la vía confiable de
 * sincronización, este push es solo una optimización de latencia.
 */
@Service
public class CvNotificationService {

    private final WebClient webClient;
    private final CvProperties cvProperties;

    @Value("${app.security.alert-service-token}")
    private String alertServiceToken;

    public CvNotificationService(CvProperties cvProperties) {
        this.cvProperties = cvProperties;
        this.webClient = WebClient.builder().build();
    }

    public void notifyRulesChanged() {
        String url = cvProperties.reloadUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + alertServiceToken)
                .retrieve()
                .toBodilessEntity()
                .subscribe(response -> { }, error -> { });
    }
}
