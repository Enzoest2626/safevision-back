package com.safevision.back.infrastructure.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.InstanceOfAssertFactories.INTEGER;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TA32 (HU07) — pruebas del envío del cooldown al CV por webhook, contra un
 * servidor HTTP local (HttpServer del JDK, sin dependencias nuevas) que
 * simula el webhook del CV. A diferencia de HttpRulesPublisherTest (con el
 * ExchangeFunction mockeado), aquí se verifica el cuerpo JSON real
 * ("required_epp" + "cooldown_seconds") que llega a cada cámara.
 */
@DisplayName("HttpRulesPublisher — cooldown EPP por webhook (CV simulado, TA32)")
class HttpRulesPublisherCooldownTest {

    private static final Long SITE_ID = 1L;

    @Test
    @Tag("CP41")
    @DisplayName("CP41 — nota solo a las cámaras con IP (2 peticiones); la cámara sin IP no recibe nada")
    void cp41_camaraSinIp_noRecibeNotificacion() throws Exception {
        System.out.println("[CP41] Envío del cooldown por webhook al CV — obra con CAM-01/CAM-02 (IP 127.0.0.1) y CAM-03 (sin IP)");
        try (SimulatedCv cv = new SimulatedCv(2)) {
            CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
            when(cameraRepository.findBySiteIdAndActiveTrue(SITE_ID)).thenReturn(Flux.just(
                    new Camera(1L, SITE_ID, 10L, "CAM-01", "Entrada", "127.0.0.1", null, true,
                            LocalDateTime.now(), "system", LocalDateTime.now(), "system"),
                    new Camera(2L, SITE_ID, 10L, "CAM-02", "Patio", "127.0.0.1", null, true,
                            LocalDateTime.now(), "system", LocalDateTime.now(), "system"),
                    new Camera(3L, SITE_ID, 10L, "CAM-03", "Almacén", null, null, true,
                            LocalDateTime.now(), "system", LocalDateTime.now(), "system")));

            HttpRulesPublisher publisher =
                    new HttpRulesPublisher(WebClient.builder(), cameraRepository, cv.port(), 5);
            // publishRules es asíncrono (subscribe interno): no lanza, solo notifica
            publisher.publishRules(SITE_ID, List.of("casco", "chaleco"), 30);

            assertThat(cv.awaitRequests(5_000))
                    .as("el CV simulado debió recibir 2 peticiones en menos de 5 s").isTrue();
            // margen para detectar peticiones de más tras liberarse el latch
            Thread.sleep(300);

            System.out.println("[CP41] Peticiones recibidas por el CV simulado: " + cv.requestCount());
            System.out.println("[CP41] Petición 1 | ruta=" + cv.path(0) + " | cuerpo=" + cv.body(0));
            System.out.println("[CP41] Petición 2 | ruta=" + cv.path(1) + " | cuerpo=" + cv.body(1));

            Map<String, Object> cuerpo1 = parseJson(cv.body(0));
            Map<String, Object> cuerpo2 = parseJson(cv.body(1));

            assertThat(cv.requestCount()).as("peticiones al CV (la cámara sin IP no se notifica)").isEqualTo(2);
            assertThat(cv.path(0)).as("ruta de la petición 1").isEqualTo("/webhook/rules");
            assertThat(cv.path(1)).as("ruta de la petición 2").isEqualTo("/webhook/rules");
            assertThat(cuerpo1.get("cooldown_seconds")).as("cooldown_seconds en la petición 1")
                    .asInstanceOf(INTEGER).isEqualTo(30);
            assertThat(cuerpo1.get("required_epp")).as("required_epp en la petición 1")
                    .asInstanceOf(list(String.class)).containsExactlyInAnyOrder("casco", "chaleco");
            assertThat(cuerpo2.get("cooldown_seconds")).as("cooldown_seconds en la petición 2")
                    .asInstanceOf(INTEGER).isEqualTo(30);
            assertThat(cuerpo2.get("required_epp")).as("required_epp en la petición 2")
                    .asInstanceOf(list(String.class)).containsExactlyInAnyOrder("casco", "chaleco");

            System.out.println("[CP41] El CV recibió exactamente 2 peticiones a /webhook/rules con cooldown_seconds=30 y required_epp=[casco, chaleco]; CAM-03 sin IP no fue notificada => PASA");
        }
    }

    @Test
    @Tag("CP42")
    @DisplayName("CP42 — cámara inalcanzable (127.0.0.2) no lanza excepción ni bloquea la notificación de la otra")
    void cp42_camaraInalcanzable_noLanzaExcepcion_yLaOtraRecibeCooldown() throws Exception {
        System.out.println("[CP42] Envío del cooldown con CAM-01 inalcanzable (127.0.0.2, nadie escucha) y CAM-02 alcanzable");
        try (SimulatedCv cv = new SimulatedCv(1)) {
            CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
            when(cameraRepository.findBySiteIdAndActiveTrue(SITE_ID)).thenReturn(Flux.just(
                    new Camera(1L, SITE_ID, 10L, "CAM-01", "Puente", "127.0.0.2", null, true,
                            LocalDateTime.now(), "system", LocalDateTime.now(), "system"),
                    new Camera(2L, SITE_ID, 10L, "CAM-02", "Oficina", "127.0.0.1", null, true,
                            LocalDateTime.now(), "system", LocalDateTime.now(), "system")));

            HttpRulesPublisher publisher =
                    new HttpRulesPublisher(WebClient.builder(), cameraRepository, cv.port(), 5);
            Throwable error = catchThrowable(() -> publisher.publishRules(SITE_ID, List.of("casco"), 45));
            assertThat(error).as("publishRules es best-effort: no debe lanzar excepción").isNull();

            assertThat(cv.awaitRequests(5_000))
                    .as("el CV simulado debió recibir 1 petición en menos de 5 s").isTrue();
            // margen para detectar peticiones de más tras liberarse el latch
            Thread.sleep(300);

            System.out.println("[CP42] Peticiones recibidas por el CV simulado: " + cv.requestCount());
            System.out.println("[CP42] Petición 1 | ruta=" + cv.path(0) + " | cuerpo=" + cv.body(0));

            Map<String, Object> cuerpo = parseJson(cv.body(0));
            assertThat(cv.requestCount()).as("peticiones al CV (exactamente 1)").isEqualTo(1);
            assertThat(cv.path(0)).as("ruta de la petición").isEqualTo("/webhook/rules");
            assertThat(cuerpo.get("cooldown_seconds")).as("cooldown_seconds")
                    .asInstanceOf(INTEGER).isEqualTo(45);
            assertThat(cuerpo.get("required_epp")).as("required_epp")
                    .asInstanceOf(list(String.class)).containsExactlyInAnyOrder("casco");

            System.out.println("[CP42] La conexión rechazada de CAM-01 no lanzó excepción y el CV recibió exactamente 1 petición con cooldown_seconds=45 => PASA");
        }
    }

    /**
     * CV simulado: servidor HTTP local del JDK enlazado al loopback en un
     * puerto efímero — guarda la ruta y el cuerpo de cada petición recibida,
     * responde 200 y libera un CountDownLatch por petición.
     */
    private static final class SimulatedCv implements AutoCloseable {

        private static final String WEBHOOK_PATH = "/webhook/rules";

        private final HttpServer server;
        private final CountDownLatch latch;
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> bodies = new CopyOnWriteArrayList<>();

        private SimulatedCv(int peticionesEsperadas) throws IOException {
            this.latch = new CountDownLatch(peticionesEsperadas);
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            this.server.createContext(WEBHOOK_PATH, exchange -> {
                String body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(body);
                byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respuesta.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(respuesta);
                }
                latch.countDown();
            });
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        /** Espera, como máximo el timeout dado, a que lleguen todas las peticiones esperadas. */
        private boolean awaitRequests(long timeoutMillis) throws InterruptedException {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        private int requestCount() {
            return bodies.size();
        }

        private String path(int index) {
            return paths.get(index);
        }

        private String body(int index) {
            return bodies.get(index);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Parsea el cuerpo JSON capturado de una petición como Map genérico. */
    private static Map<String, Object> parseJson(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (IOException ex) {
            throw new UncheckedIOException("Cuerpo capturado no es JSON válido: " + json, ex);
        }
    }
}
