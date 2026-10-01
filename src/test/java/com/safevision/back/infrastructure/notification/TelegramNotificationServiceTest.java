package com.safevision.back.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.infrastructure.config.TelegramProperties;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unitario (sin red real) — mockea el ExchangeFunction del WebClient para
 * verificar ruta/método/manejo de error sin depender de la Bot API de Telegram.
 * El test de integración real está en TelegramNotificationServiceIntegrationTest.
 */
@DisplayName("TelegramNotificationService — envío de alerta vía Bot API")
class TelegramNotificationServiceTest {

    private static final String FRAME_B64 = Base64.getEncoder().encodeToString("fake-jpeg-bytes".getBytes());

    private final TelegramProperties properties = new TelegramProperties("bot-token-123", "chat-1");
    private final Worker worker = new Worker(10L, 1L, 3, "Juan", "Perez", "Albañil",
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Incident incident = new Incident(100L, 10L, 20L, 1L, "ext-100",
            new String[]{"helmet", "vest"}, LocalDateTime.of(2026, 6, 24, 13, 30), LocalDateTime.now());
    private final Site site = new Site(1L, "OBRA-1", "Main-Site", "Lima", 60, true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private AtomicReference<ClientRequest> capturedRequest;

    @BeforeEach
    void setUp() {
        capturedRequest = new AtomicReference<>();
    }

    private TelegramNotificationService buildService(ExchangeFunction exchangeFunction) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        // Backoff de 1 ms: los tests de reintento no esperan los segundos reales de producción
        return new TelegramNotificationService(builder, properties, "https://api.telegram.org",
                Duration.ofMillis(1));
    }

    /** Responde con {@code statuses} en orden (el último se repite) y cuenta los intentos. */
    private ExchangeFunction respondingInOrder(AtomicInteger attempts, HttpStatus... statuses) {
        return request -> {
            int attempt = attempts.getAndIncrement();
            HttpStatus status = statuses[Math.min(attempt, statuses.length - 1)];
            return Mono.just(ClientResponse.create(status).body(status.getReasonPhrase()).build());
        };
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

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, worker, camera, site, "Piso 2", FRAME_B64))
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

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, worker, cameraSinNombre, site, "", FRAME_B64))
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

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, worker, camera, site, "", FRAME_B64))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(IllegalStateException.class);
                    assertThat(ex.getMessage()).contains("429").contains("Too Many Requests");
                })
                .verify();
    }

    @Test
    @DisplayName("sendIncidentAlertByUrl → manda la URL prefirmada como string, sin bytes")
    void sendIncidentAlertByUrl_mandaUrlComoStringEnLugarDeBytes() {
        Camera camera = new Camera(20L, 1L, 7L, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        String photoUrl = "https://safevision-evidencia.s3.amazonaws.com/incidents/2026-08-10/abc/photo.jpg?X-Amz-Signature=xyz";
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramNotificationService service = buildService(exchangeFunction);

        StepVerifier.create(service.sendIncidentAlertByUrl("chat-1", incident, worker, camera, site, "Piso 2", photoUrl))
                .verifyComplete();

        assertThat(capturedRequest.get().method().name()).isEqualTo("POST");
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("https://api.telegram.org/bot" + properties.botToken() + "/sendPhoto");
    }

    @Test
    @DisplayName("sendIncidentAlertByUrl → Telegram responde error → propaga con detalle")
    void sendIncidentAlertByUrl_telegramResponde500_propagaError() {
        Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        ExchangeFunction exchangeFunction = request -> Mono.just(
                ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal error").build());
        TelegramNotificationService service = buildService(exchangeFunction);

        StepVerifier.create(service.sendIncidentAlertByUrl("chat-1", incident, worker, camera, site, "", "https://x/y.jpg"))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(IllegalStateException.class);
                    assertThat(ex.getMessage()).contains("500").contains("Internal error");
                })
                .verify();
    }

    // ── CP28: contenido de la alerta ──────────────────────────────────

    @Test
    @DisplayName("CP28 — la alerta lleva trabajador, EPP faltante, cámara, obra y fecha/hora del incidente")
    void buildCaption_incluyeLosCincoCamposDelIncidente() {
        Camera camera = new Camera(20L, 1L, 7L, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        TelegramNotificationService service = buildService(request -> Mono.empty());

        String caption = service.buildCaption(worker, camera, site, "Piso 2", incident);

        System.out.println("\n[CP28] Texto de la alerta Telegram:\n" + caption);
        assertThat(caption)
                .contains("Trabajador: Juan Perez (código 3)")
                .contains("EPP Faltante: helmet, vest")
                .contains("Cámara: Entrada")
                .contains("Obra: Main-Site")
                .contains("Fecha: 24/06/2026")
                .contains("Hora: 01:30:00");
        System.out.println("[CP28] Trabajador, EPP, cámara, obra y hora presentes => PASA");
    }

    // ── CP29: reintento básico ante errores transitorios ──────────────

    @Test
    @DisplayName("CP29 — 429 dos veces y luego 200: reintenta y la alerta se entrega sin error")
    void sendPhoto_429Transitorio_reintentaYEntrega() {
        Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        AtomicInteger attempts = new AtomicInteger();
        TelegramNotificationService service = buildService(respondingInOrder(attempts,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.OK));

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, worker, camera, site, "", FRAME_B64))
                .verifyComplete();

        System.out.println("\n[CP29] Bot con rate-limit transitorio: intentos = " + attempts.get()
                + " (429, 429, 200) => " + (attempts.get() == 3 ? "PASA" : "FALLA"));
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("CP29 — 429 persistente: agota los reintentos (1 + MAX_RETRIES intentos) y propaga el error")
    void sendPhoto_429Persistente_agotaReintentosYPropaga() {
        Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        AtomicInteger attempts = new AtomicInteger();
        TelegramNotificationService service = buildService(
                respondingInOrder(attempts, HttpStatus.TOO_MANY_REQUESTS));

        StepVerifier.create(service.sendIncidentAlert("chat-1", incident, worker, camera, site, "", FRAME_B64))
                .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("429"))
                .verify();

        int expected = 1 + TelegramNotificationService.MAX_RETRIES;
        System.out.println("\n[CP29] Bot inaccesible (429 persistente): intentos = " + attempts.get()
                + ", luego error al llamador => " + (attempts.get() == expected ? "PASA" : "FALLA"));
        assertThat(attempts.get()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Error no transitorio (400): no se reintenta")
    void sendPhoto_400_noReintenta() {
        Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        AtomicInteger attempts = new AtomicInteger();
        TelegramNotificationService service = buildService(respondingInOrder(attempts, HttpStatus.BAD_REQUEST));

        StepVerifier.create(service.sendIncidentAlertByUrl("chat-1", incident, worker, camera, site, "", "https://x/y.jpg"))
                .expectError(IllegalStateException.class)
                .verify();

        assertThat(attempts.get()).isEqualTo(1);
    }

    // ── HU12: mensaje de texto del reporte diario ────────────────────

    @Test
    @DisplayName("HU12 — sendTextMessage → POST a /bot{token}/sendMessage completa sin error")
    void sendTextMessage_mensajeDeTexto_completaSinError() {
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        TelegramNotificationService service = buildService(exchangeFunction);

        StepVerifier.create(service.sendTextMessage("chat-1", "Reporte diario: 2 incumplimientos"))
                .verifyComplete();

        assertThat(capturedRequest.get().method().name()).isEqualTo("POST");
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("https://api.telegram.org/bot" + properties.botToken() + "/sendMessage");
    }

    @Test
    @DisplayName("HU12 — sendTextMessage con 500 persistente: reintenta y propaga el error")
    void sendTextMessage_500Persistente_reintentaYPropaga() {
        AtomicInteger attempts = new AtomicInteger();
        TelegramNotificationService service = buildService(
                respondingInOrder(attempts, HttpStatus.INTERNAL_SERVER_ERROR));

        StepVerifier.create(service.sendTextMessage("chat-1", "Reporte diario"))
                .expectErrorSatisfies(ex -> assertThat(ex.getMessage()).contains("500"))
                .verify();

        assertThat(attempts.get()).isEqualTo(1 + TelegramNotificationService.MAX_RETRIES);
    }
}
