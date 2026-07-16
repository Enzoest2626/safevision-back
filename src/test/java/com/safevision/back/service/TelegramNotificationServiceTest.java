package com.safevision.back.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.config.TelegramProperties;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unitario (sin red real) — mockea el ExchangeFunction del WebClient para
 * verificar ruta/método/manejo de error sin depender de la Bot API de Telegram.
 * El test de integración real está en TelegramNotificationServiceIntegrationTest.
 */
@DisplayName("TelegramNotificationService — envío de alerta vía Bot API (HU10)")
class TelegramNotificationServiceTest {

    private static final String FRAME_B64 = Base64.getEncoder().encodeToString("fake-jpeg-bytes".getBytes());

    private final TelegramProperties properties = new TelegramProperties("bot-token-123", "chat-1");
    private final Incident incident = new Incident(100L, 10L, 20L, 1L,
            new String[]{"helmet", "vest"}, LocalDateTime.of(2026, 6, 24, 13, 30), LocalDateTime.now());
    private final Site site = new Site(1L, "Main-Site", "Lima", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private AtomicReference<ClientRequest> capturedRequest;

    @BeforeEach
    void setUp() {
        capturedRequest = new AtomicReference<>();
    }

    private TelegramNotificationService buildService(ExchangeFunction exchangeFunction) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new TelegramNotificationService(builder, properties);
    }

    @Test
    @DisplayName("Cámara con nombre y zona → POST a /bot{token}/sendPhoto completa sin error")
    void sendIncidentAlert_conNombreYZona_completaSinError() {
        Camera camera = new Camera(20L, 1L, 7L, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramNotificationService service = buildService(exchangeFunction);

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, camera, site, "Piso 2", FRAME_B64))
                .verifyComplete();

        assertThat(capturedRequest.get().method().name()).isEqualTo("POST");
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("https://api.telegram.org/bot" + properties.botToken() + "/sendPhoto");
    }

    @Test
    @DisplayName("Cámara sin nombre y sin zona → usa el code como fallback y omite la línea de zona")
    void sendIncidentAlert_sinNombreNiZona_usaCodeComoFallback() {
        Camera cameraSinNombre = new Camera(21L, 1L, null, "CAM-02", null, "10.0.0.6", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramNotificationService service = buildService(exchangeFunction);

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, cameraSinNombre, site, "", FRAME_B64))
                .verifyComplete();

        assertThat(capturedRequest.get()).isNotNull();
    }

    @Test
    @DisplayName("Telegram responde 429 → el Mono termina en error con status y body del bot")
    void sendIncidentAlert_telegramResponde429_propagaErrorConDetalle() {
        Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        ExchangeFunction exchangeFunction = request -> Mono.just(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Too Many Requests")
                        .build());
        TelegramNotificationService service = buildService(exchangeFunction);

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, camera, site, "", FRAME_B64))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(IllegalStateException.class);
                    assertThat(ex.getMessage()).contains("429").contains("Too Many Requests");
                })
                .verify();
    }
}
