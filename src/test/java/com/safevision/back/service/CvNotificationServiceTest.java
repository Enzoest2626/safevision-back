package com.safevision.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.safevision.back.config.CvProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class CvNotificationServiceTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void sinReloadUrlConfigurada_noHaceNadaYNoLanzaExcepcion(String reloadUrl) {
        CvNotificationService service = new CvNotificationService(new CvProperties(reloadUrl));

        assertThatCode(service::notifyRulesChanged).doesNotThrowAnyException();
    }

    @Test
    void conReloadUrlInvalida_noPropagaExcepcion() {
        // best-effort: incluso con una URL con formato invalido, no debe
        // interrumpir el flujo de updateForSite() — el error queda en el
        // consumer de error del subscribe(), silencioso por diseño.
        CvNotificationService service = new CvNotificationService(new CvProperties("no-es-una-url"));

        assertThatCode(service::notifyRulesChanged).doesNotThrowAnyException();
    }

    /**
     * CP32 (HU04-CA3) — prueba que, cuando cambian las reglas EPP, el
     * backend SÍ envía un POST real (no simulado) al webhook de recarga del
     * módulo CV, con el token correcto. Es la mitad "backend" de la
     * evidencia de CA3 ("aplica la nueva regla de forma inmediata sin
     * interrumpir el monitoreo en curso") — la otra mitad (que el CV
     * actualiza el tracker en caliente al recibir ese POST) ya está probada
     * en el repo computer-vision: tests/test_reload_server.py.
     */
    @Test
    void conReloadUrlValida_envioPostRealConTokenCorrecto() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();

        server.createContext("/reload-params", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String url = "http://localhost:" + port + "/reload-params";
            CvNotificationService service = new CvNotificationService(new CvProperties(url));
            ReflectionTestUtils.setField(service, "alertServiceToken", "test-token-real");

            service.notifyRulesChanged();
            boolean arrived = received.await(5, TimeUnit.SECONDS);

            System.out.println("\n[CP32] Push real de recarga de parametros al modulo CV:");
            System.out.println("       Servidor real recibio la llamada: " + arrived);
            System.out.println("       Metodo HTTP: " + capturedMethod.get());
            System.out.println("       Ruta: " + capturedPath.get());
            System.out.println("       Header Authorization: " + capturedAuth.get());
            boolean correcto = arrived
                    && "POST".equals(capturedMethod.get())
                    && "/reload-params".equals(capturedPath.get())
                    && "Bearer test-token-real".equals(capturedAuth.get());
            System.out.println("[CP32] POST real enviado con metodo, ruta y token correctos: "
                    + correcto + " => PASA");

            assertThat(arrived).isTrue();
            assertThat(capturedMethod.get()).isEqualTo("POST");
            assertThat(capturedPath.get()).isEqualTo("/reload-params");
            assertThat(capturedAuth.get()).isEqualTo("Bearer test-token-real");
        } finally {
            server.stop(0);
        }
    }
}
