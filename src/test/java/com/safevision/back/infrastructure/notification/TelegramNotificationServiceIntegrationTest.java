package com.safevision.back.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Integración real contra la Bot API de Telegram (sin mocks).
 * Requiere TELEGRAM_BOT_TOKEN y TELEGRAM_CHAT_ID reales en el entorno; si
 * faltan, JUnit omite el test (no rompe el build en otras máquinas ni en CI).
 *
 * Cada corrida envía mensajes reales al chat configurado — no es un test
 * silencioso, aparece evidencia real en Telegram.
 */
@EnabledIfEnvironmentVariable(named = "TELEGRAM_BOT_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TELEGRAM_CHAT_ID", matches = ".+")
@DisplayName("TelegramNotificationService — integración real, tiempo de respuesta bajo carga")
class TelegramNotificationServiceIntegrationTest {

    // 1x1 px JPEG válido en base64 — evidencia mínima para sendPhoto
    private static final String FRAME_B64 =
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX/9k=";

    private static final int N = 3; // bajo a propósito: evita el rate-limit de Telegram (~1 msg/s por chat)

    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Site site = new Site(1L, "OBRA-1", "Main-Site", "Lima", 60, true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private TelegramNotificationService buildService() {
        TelegramProperties props = new TelegramProperties(
                System.getenv("TELEGRAM_BOT_TOKEN"),
                System.getenv("TELEGRAM_CHAT_ID"));
        return new TelegramNotificationService(WebClient.builder(), props);
    }

    private Incident sampleIncident(int seq) {
        return new Incident((long) seq, 10L, 20L, 1L, "ext-" + seq,
                new String[]{"casco"}, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("N envíos secuenciales y concurrentes responden en <=5s cada uno")
    void tiempoDeRespuestaBajoCarga_menorOIgualA5s() {
        TelegramNotificationService service = buildService();
        String chatId = System.getenv("TELEGRAM_CHAT_ID");

        System.out.println("\nTiempo de respuesta real de Telegram bajo carga (N=" + N + " secuenciales + " + N + " concurrentes):");

        List<Long> secuencialesMs = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            long start = System.currentTimeMillis();
            service.sendIncidentAlert(chatId, sampleIncident(i), camera, site, "", FRAME_B64).block();
            long elapsed = System.currentTimeMillis() - start;
            secuencialesMs.add(elapsed);
            System.out.println(" Secuencial #" + (i + 1) + " → " + elapsed + " ms");
        }

        List<Long> concurrentesMs = Flux.range(0, N)
                .flatMap(i -> {
                    long start = System.currentTimeMillis();
                    return service.sendIncidentAlert(chatId, sampleIncident(100 + i), camera, site, "", FRAME_B64)
                            .then(Mono.fromCallable(() -> System.currentTimeMillis() - start));
                })
                .collectList()
                .block();

        for (int i = 0; i < concurrentesMs.size(); i++) {
            System.out.println(" Concurrente #" + (i + 1) + " → " + concurrentesMs.get(i) + " ms");
        }

        boolean todasBajoLimite = secuencialesMs.stream().allMatch(ms -> ms <= 5000)
                && concurrentesMs.stream().allMatch(ms -> ms <= 5000);
        System.out.println("Todas las respuestas <= 5000 ms: " + todasBajoLimite
                + " => " + (todasBajoLimite ? "PASA" : "FALLA"));

        assertThat(secuencialesMs).allMatch(ms -> ms <= 5000);
        assertThat(concurrentesMs).allMatch(ms -> ms <= 5000);
    }
}
