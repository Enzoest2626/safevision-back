package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.webhook.HttpCameraConfigPublisher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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

/**
 * CP40 (HU08, CA1) — la configuración de cámara que registra el backend sí
 * llega al módulo de detección. Se usan {@link CameraService} y
 * {@link HttpCameraConfigPublisher} REALES: el publisher apunta al puerto del
 * CV simulado (HttpServer del JDK, misma técnica que HttpRulesPublisherCooldownTest)
 * con timeout de 5 s, y las cámaras usan ip_address 127.0.0.1, que es donde
 * escucha el CV simulado. Solo CameraRepositoryPort está mockeado.
 *
 * <p>publishConfig es asíncrono (el publisher hace subscribe interno): cuando
 * sí debe llegar una petición se espera con latch, máximo 5 s; cuando no debe
 * llegar ninguna se deja pasar 500 ms y se comprueba que el servidor no recibió
 * nada.
 */
@DisplayName("CameraConfigPushFunctionalTest — configuración de cámara aplicada en el CV (HU08, CA1)")
class CameraConfigPushFunctionalTest {

    // ── CP40 — alta con zona ──────────────────────────────────────────

    @Test
    @Tag("CP40")
    @DisplayName("CP40 — alta de cámara CON zona notifica su config al CV simulado")
    void cp40_altaConZona_notificaConfigAlCv() throws Exception {
        System.out.println("[CP40] Alta de cámara CON zona (CAM-40, rtsp://10.0.0.40/stream) contra CV simulado");
        try (SimulatedCv cv = new SimulatedCv(1)) {
            CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
            when(cameraRepository.existsByCode("CAM-40")).thenReturn(Mono.just(false));
            when(cameraRepository.save(any(Camera.class)))
                    .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0), 41L)));

            CameraService service = new CameraService(cameraRepository, publisherFor(cv));
            CameraRequest request = new CameraRequest(1L, 10L, "CAM-40", "Entrada",
                    "127.0.0.1", "rtsp://10.0.0.40/stream");

            StepVerifier.create(service.create(request, "supervisor1"))
                    .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-40"))
                    .verifyComplete();

            assertThat(cv.awaitRequests(5_000))
                    .as("el CV simulado debió recibir la petición en menos de 5 s").isTrue();
            // margen para detectar peticiones de más tras liberarse el latch
            Thread.sleep(500);

            System.out.println("[CP40] Peticiones recibidas por el CV simulado: " + cv.requestCount());
            System.out.println("[CP40] Petición 1 | ruta=" + cv.path(0) + " | cuerpo=" + cv.body(0));

            Map<String, Object> cuerpo = parseJson(cv.body(0));
            assertThat(cv.requestCount()).as("peticiones al CV (exactamente 1)").isEqualTo(1);
            assertThat(cv.path(0)).as("ruta de la petición").isEqualTo("/webhook/config");
            assertThat(cuerpo.get("camera_code")).as("camera_code").isEqualTo("CAM-40");
            assertThat(cuerpo.get("rtsp_url")).as("rtsp_url").isEqualTo("rtsp://10.0.0.40/stream");
            assertThat(cuerpo.get("active")).as("active").isEqualTo(Boolean.TRUE);

            System.out.println("[CP40] Exactamente 1 POST a /webhook/config con camera_code=CAM-40, "
                    + "rtsp_url=rtsp://10.0.0.40/stream y active=true => PASA");
        }
    }

    // ── CP40 — alta sin zona ──────────────────────────────────────────

    @Test
    @Tag("CP40")
    @DisplayName("CP40 — alta de cámara SIN zona queda registrada pero el CV no recibe ninguna petición")
    void cp40_altaSinZona_noNotificaNadaAlCv() throws Exception {
        System.out.println("[CP40] Alta de cámara SIN zona (zoneId nulo) — el CV simulado no debe recibir nada");
        try (SimulatedCv cv = new SimulatedCv(1)) {
            CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
            when(cameraRepository.existsByCode("CAM-40-SINZONA")).thenReturn(Mono.just(false));
            when(cameraRepository.save(any(Camera.class)))
                    .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0), 42L)));

            CameraService service = new CameraService(cameraRepository, publisherFor(cv));
            CameraRequest request = new CameraRequest(1L, null, "CAM-40-SINZONA", "Andamio",
                    "127.0.0.1", "rtsp://10.0.0.40/stream");

            StepVerifier.create(service.create(request, "supervisor1"))
                    .assertNext(response -> assertThat(response.zoneId()).isNull())
                    .verifyComplete();

            verify(cameraRepository).save(any(Camera.class));
            System.out.println("[CP40] Cámara guardada en el repositorio (save invocado) con zoneId=null");

            // publishConfig es asíncrono: margen de 500 ms para detectar una petición no deseada
            Thread.sleep(500);
            System.out.println("[CP40] Peticiones recibidas por el CV simulado: " + cv.requestCount());

            assertThat(cv.requestCount())
                    .as("la cámara sin zona no debe notificar su config").isZero();

            System.out.println("[CP40] Cámara registrada y 0 peticiones al CV simulado => PASA");
        }
    }

    // ── CP40 — edición y desactivación ────────────────────────────────

    @Test
    @Tag("CP40")
    @DisplayName("CP40 — editar la URL RTSP notifica la URL nueva y desactivar notifica active=false")
    void cp40_edicionYDesactivacion_notificanUrlNuevaYBaja() throws Exception {
        System.out.println("[CP40] Edición de rtspUrl y desactivación de CAM-41 — 2 peticiones esperadas al CV");
        try (SimulatedCv cv = new SimulatedCv(2)) {
            CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
            Camera existente = new Camera(41L, 1L, 10L, "CAM-41", "Andamio", "127.0.0.1",
                    "rtsp://10.0.0.41/viejo", true, LocalDateTime.now(), "system",
                    LocalDateTime.now(), "system");
            when(cameraRepository.findById(41L)).thenReturn(Mono.just(existente));
            when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            CameraService service = new CameraService(cameraRepository, publisherFor(cv));

            // 1) edición: cambia la URL RTSP
            CameraRequest edicion = new CameraRequest(1L, 10L, "CAM-41", "Andamio",
                    "127.0.0.1", "rtsp://10.0.0.41/nuevo");
            StepVerifier.create(service.update(41L, edicion, "supervisor1"))
                    .assertNext(response -> assertThat(response.rtspUrl()).isEqualTo("rtsp://10.0.0.41/nuevo"))
                    .verifyComplete();

            assertThat(cv.awaitCount(1, 5_000))
                    .as("el CV simulado debió recibir la edición en menos de 5 s").isTrue();
            // margen para detectar peticiones de más tras liberarse el latch
            Thread.sleep(300);

            System.out.println("[CP40] Peticiones tras la edición: " + cv.requestCount());
            System.out.println("[CP40] Petición 1 | ruta=" + cv.path(0) + " | cuerpo=" + cv.body(0));

            Map<String, Object> cuerpoEdicion = parseJson(cv.body(0));
            assertThat(cv.requestCount()).as("peticiones tras la edición (exactamente 1)").isEqualTo(1);
            assertThat(cuerpoEdicion.get("rtsp_url")).as("rtsp_url tras la edición")
                    .isEqualTo("rtsp://10.0.0.41/nuevo");
            assertThat(cuerpoEdicion.get("active")).as("active tras la edición").isEqualTo(Boolean.TRUE);

            // 2) desactivación: la misma cámara debe notificar active=false
            Camera actualizada = new Camera(41L, 1L, 10L, "CAM-41", "Andamio", "127.0.0.1",
                    "rtsp://10.0.0.41/nuevo", true, LocalDateTime.now(), "system",
                    LocalDateTime.now(), "system");
            when(cameraRepository.findById(41L)).thenReturn(Mono.just(actualizada));

            StepVerifier.create(service.delete(41L, "supervisor1")).verifyComplete();

            assertThat(cv.awaitRequests(5_000))
                    .as("el CV simulado debió recibir la desactivación en menos de 5 s").isTrue();
            Thread.sleep(300);

            System.out.println("[CP40] Peticiones tras la desactivación: " + cv.requestCount());
            System.out.println("[CP40] Petición 2 | ruta=" + cv.path(1) + " | cuerpo=" + cv.body(1));

            Map<String, Object> cuerpoBaja = parseJson(cv.body(1));
            assertThat(cv.requestCount()).as("peticiones totales al CV (exactamente 2)").isEqualTo(2);
            assertThat(cuerpoBaja.get("camera_code")).as("camera_code en la desactivación").isEqualTo("CAM-41");
            assertThat(cuerpoBaja.get("active")).as("active en la desactivación").isEqualTo(Boolean.FALSE);

            System.out.println("[CP40] 1 POST con rtsp_url=rtsp://10.0.0.41/nuevo y active=true, "
                    + "y 1 POST con active=false => PASA");
        }
    }

    // ── CP40 — CV inalcanzable ────────────────────────────────────────

    @Test
    @Tag("CP40")
    @DisplayName("CP40 — CV inalcanzable (127.0.0.2): el alta completa normalmente y sin excepción")
    void cp40_cvInalcanzable_altaCompletaSinExcepcion() throws Exception {
        System.out.println("[CP40] Alta de cámara con zona e ip_address 127.0.0.2 (nadie escucha en ese host)");
        try (SimulatedCv cv = new SimulatedCv(1)) {
            CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
            when(cameraRepository.existsByCode("CAM-43")).thenReturn(Mono.just(false));
            when(cameraRepository.save(any(Camera.class)))
                    .thenAnswer(inv -> Mono.just(withId(inv.getArgument(0), 43L)));

            HttpCameraConfigPublisher publisher = publisherFor(cv);
            CameraService service = new CameraService(cameraRepository, publisher);
            CameraRequest request = new CameraRequest(1L, 10L, "CAM-43", "Puesto norte",
                    "127.0.0.2", "rtsp://10.0.0.43/stream");

            // publishConfig es best-effort: si la conexión rechazara el alta,
            // el error se traga en el publisher y el Mono no recibe excepción.
            StepVerifier.create(service.create(request, "supervisor1"))
                    .assertNext(response -> {
                        assertThat(response.id()).isEqualTo(43L);
                        assertThat(response.ipAddress()).isEqualTo("127.0.0.2");
                    })
                    .verifyComplete();

            verify(cameraRepository).save(any(Camera.class));
            System.out.println("[CP40] StepVerifier terminó sin error | cámara guardada con id=43");

            // Verificación explícita de "sin excepción": la publicación hacia un
            // host donde nadie escucha tampoco lanza nada (es best-effort).
            Camera camaraInalcanzable = new Camera(43L, 1L, 10L, "CAM-43", "Puesto norte",
                    "127.0.0.2", "rtsp://10.0.0.43/stream", true, LocalDateTime.now(), "system",
                    LocalDateTime.now(), "system");
            Throwable error = catchThrowable(() -> publisher.publishCameraConfig(camaraInalcanzable));
            assertThat(error)
                    .as("publishCameraConfig es best-effort: no debe lanzar excepción").isNull();

            // margen para que las publicaciones asíncronas (y su fallo) ocurran
            Thread.sleep(500);
            System.out.println("[CP40] Peticiones recibidas por el CV simulado: " + cv.requestCount());

            assertThat(cv.requestCount())
                    .as("nadie escucha en 127.0.0.2: la petición no llegó a ningún servidor").isZero();

            System.out.println("[CP40] Alta completada con la cámara guardada, sin excepción "
                    + "y 0 peticiones recibidas => PASA");
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────

    /** Publisher real apuntando al puerto del CV simulado, con timeout de 5 s. */
    private static HttpCameraConfigPublisher publisherFor(SimulatedCv cv) {
        return new HttpCameraConfigPublisher(WebClient.builder(), cv.port(), 5);
    }

    /** Devuelve la cámara recibida con el id asignado, como lo haría la base de datos. */
    private static Camera withId(Camera camera, Long id) {
        return new Camera(id, camera.siteId(), camera.zoneId(), camera.code(), camera.name(),
                camera.ipAddress(), camera.rtspUrl(), camera.active(),
                camera.createdAt(), camera.createdBy(), camera.updatedAt(), camera.updatedBy());
    }

    /** Parsea el cuerpo JSON capturado de una petición como Map genérico. */
    private static Map<String, Object> parseJson(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (IOException ex) {
            throw new UncheckedIOException("Cuerpo capturado no es JSON válido: " + json, ex);
        }
    }

    /**
     * CV simulado: servidor HTTP local del JDK enlazado al loopback en un puerto
     * efímero — guarda la ruta y el cuerpo de cada petición recibida, responde 200
     * y libera un CountDownLatch por petición (misma técnica que
     * HttpRulesPublisherCooldownTest). El contexto es la raíz "/" para que
     * quede registrada cualquier petición, no solo la del webhook.
     */
    private static final class SimulatedCv implements AutoCloseable {

        private final HttpServer server;
        private final CountDownLatch latch;
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> bodies = new CopyOnWriteArrayList<>();

        private SimulatedCv(int peticionesEsperadas) throws IOException {
            this.latch = new CountDownLatch(peticionesEsperadas);
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            this.server.createContext("/", exchange -> {
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

        /** Espera, como máximo el timeout dado, a que se registren al menos las peticiones indicadas. */
        private boolean awaitCount(int esperadas, long timeoutMillis) throws InterruptedException {
            long limite = System.currentTimeMillis() + timeoutMillis;
            while (bodies.size() < esperadas) {
                if (System.currentTimeMillis() >= limite) {
                    return false;
                }
                Thread.sleep(20);
            }
            return true;
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
}
