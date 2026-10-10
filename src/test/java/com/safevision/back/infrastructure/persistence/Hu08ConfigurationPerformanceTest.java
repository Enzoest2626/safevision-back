package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.infrastructure.security.JwtService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.web.dto.CameraResponse;
import com.safevision.back.infrastructure.web.dto.SetActiveRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import com.safevision.back.infrastructure.web.dto.SiteResponse;
import com.safevision.back.infrastructure.web.dto.ZoneRequest;
import com.safevision.back.infrastructure.web.dto.ZoneResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CP45 (HU08) — "Tiempo de respuesta de la API de configuración": mide la
 * latencia de las cuatro operaciones de configuración más frecuentes contra un
 * PostgreSQL real (Testcontainers), con 10 llamadas de calentamiento (no
 * medidas) y 50 llamadas medidas secuenciales por operación (N >= 30, la
 * muestra mínima que exige CP45) e informa el percentil 95 de cada una.
 *
 * <p>Infraestructura copiada de {@link Hu08ConfigurationIntegrationTest}
 * (servidor real en RANDOM_PORT con {@code spring.webflux.base-path=/api/v1},
 * contenedor {@code postgres:16-alpine}, schema desde
 * {@code docs/init-schema.sql} y JWT del bean {@link JwtService}); ese archivo
 * no se modifica.
 *
 * <p>Las cámaras creadas durante la medición (con zona e ipAddress) notifican
 * su configuración al módulo CV por HTTP best-effort (fire-and-forget, ver
 * {@code HttpCameraConfigPublisher}) — esa notificación es asíncrona y no se
 * mide: lo cronometrado es la respuesta de la API hasta recibir el cuerpo
 * completo.
 *
 * <p>Supera el límite de 150 líneas por ser una prueba de rendimiento: las
 * cuatro operaciones comparten un único contexto de Spring + contenedor y un
 * solo helper de medición; dividirla en varias clases solo multiplicaría
 * contenedores de Postgres sin ganar claridad.
 *
 * <p>Requiere Docker encendido (Testcontainers levanta el Postgres).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // src/test/resources/application.yml se antepone al application.yml
        // principal (el que define spring.webflux.base-path), así que el prefijo
        // se declara explícitamente para que el servidor real lo monte — mismo
        // valor que producción.
        properties = "spring.webflux.base-path=/api/v1")
@Testcontainers
// src/test/resources/application.yml (usado por el resto de los tests, sin
// perfil activo) no trae s3.*/telegram.* — hacen falta para levantar el
// contexto completo. "dev" sí las trae, con sus mismos defaults inofensivos.
@ActiveProfiles("dev")
@DisplayName("Hu08ConfigurationPerformanceTest — CP45 latencia de la API de configuración contra Postgres real (HU08)")
class Hu08ConfigurationPerformanceTest {

    /** Llamadas de calentamiento por operación — no entran en la muestra. */
    private static final int LLAMADAS_CALIENTAMIENTO = 10;

    /** Llamadas medidas por operación (CP45 exige N >= 30; aquí 50). */
    private static final int LLAMADAS_MEDIDAS = 50;

    /**
     * Umbral PROPUESTO para CP45: P95 <= 1000 ms por operación. No es un
     * umbral definido por la HU08 — es una propuesta pendiente de validación
     * del equipo.
     */
    private static final long UMBRAL_P95_MS = 1000;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                        POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void initSchema() throws Exception {
        // docs/init-schema.sql es la fuente única de verdad del schema — se
        // ejecuta tal cual contra el contenedor, sin copia paralela en test/resources.
        String schema = Files.readString(Path.of("docs/init-schema.sql"));
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = conn.createStatement()) {
            statement.execute(schema);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        // JWT generado por el bean real — el mismo filtro que valida lo emite
        // con el mismo secreto; no se hace login ni se usan credenciales.
        String token = jwtService.generateToken("admin", "ADMIN");
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    @Test
    @Tag("CP45")
    @DisplayName("CP45 — tiempo de respuesta de la API de configuración (P95)")
    void cp45_latenciaDeLaApiDeConfiguracion_p95DentroDelUmbralPropuesto() {
        System.out.println("\n[CP45] Tiempo de respuesta de la API de configuración (P95) — HU08:");

        // Preparación (no medida): obra + zona + contacto con Chat ID.
        String sufijo = Long.toString(System.nanoTime());
        Long siteId = crearObraOk("SITE-CP45-" + sufijo, "Obra CP45 " + sufijo);
        Long zoneId = crearZonaOk(siteId, "ZONE-CP45-" + sufijo, "Zona CP45 " + sufijo);
        Long contactId = crearContactoOk(siteId, "Supervisor CP45 " + sufijo,
                "+51 999 444 444", "5000005");

        // 1) POST /api/v1/cameras — alta con zona; el código debe ser único por
        //    llamada, así que se genera con nanoTime en cada petición. Cada alta
        //    dispara además la notificación best-effort de la config al CV
        //    (fire-and-forget, asíncrona — ver HttpCameraConfigPublisher): esa
        //    notificación no se mide.
        Peticion altaCamara = () -> crearCamara(siteId, zoneId, "CAM-CP45-" + System.nanoTime());
        // 2) GET /api/v1/cameras — listado.
        Peticion listadoCamaras = this::listarCamaras;
        // 3) PUT .../active — alterna false/true en cada llamada (arranca en false).
        AtomicBoolean proximoActivo = new AtomicBoolean(false);
        Peticion alternarActivo = () -> cambiarActivo(siteId, contactId,
                proximoActivo.getAndSet(!proximoActivo.get()));
        // 4) GET .../contacts — listado.
        Peticion listadoContactos = () -> listarContactos(siteId);

        List<Estadisticas> resultados = List.of(
                medir("POST /api/v1/cameras (alta con zona)", HttpStatus.CREATED, altaCamara),
                medir("GET /api/v1/cameras (listado)", HttpStatus.OK, listadoCamaras),
                medir("PUT /api/v1/sites/{siteId}/contacts/{contactId}/active (alternando)",
                        HttpStatus.OK, alternarActivo),
                medir("GET /api/v1/sites/{siteId}/contacts (listado)", HttpStatus.OK, listadoContactos));

        for (Estadisticas estadisticas : resultados) {
            System.out.println("       " + estadisticas.operacion()
                    + " | N=" + estadisticas.n()
                    + " | mín=" + formatoMs(estadisticas.minimo())
                    + " | P50=" + formatoMs(estadisticas.p50())
                    + " | P95=" + formatoMs(estadisticas.p95())
                    + " | máx=" + formatoMs(estadisticas.maximo())
                    + " | umbral P95=" + UMBRAL_P95_MS + " ms");
        }

        for (Estadisticas estadisticas : resultados) {
            assertThat(estadisticas.p95())
                    .as("P95 de la operación %s (N = %d llamadas medidas)",
                            estadisticas.operacion(), estadisticas.n())
                    .isLessThanOrEqualTo(UMBRAL_P95_MS);
        }

        System.out.println("[CP45] P95 de las 4 operaciones <= " + UMBRAL_P95_MS
                + " ms (N = " + LLAMADAS_MEDIDAS + " por operación) => PASA");
    }

    // ── Helpers HTTP (copiados/adaptados de Hu08ConfigurationIntegrationTest) ──

    private EntityExchangeResult<ApiEnvelope<SiteResponse>> crearObra(String code, String name) {
        return client.post().uri("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteRequest(code, name, null))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteResponse>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<ZoneResponse>> crearZona(Long siteId, String code, String name) {
        return client.post().uri("/api/v1/sites/{siteId}/zones", siteId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ZoneRequest(code, name))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<ZoneResponse>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<SiteContactResponse>> crearContacto(
            Long siteId, String name, String phone, String telegramChatId) {
        return client.post().uri("/api/v1/sites/{siteId}/contacts", siteId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest(name, phone, telegramChatId))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteContactResponse>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<CameraResponse>> crearCamara(Long siteId, Long zoneId, String code) {
        return client.post().uri("/api/v1/cameras")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CameraRequest(siteId, zoneId, code, "Camara " + code, "127.0.0.1", null))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<CameraResponse>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<SiteContactResponse>> cambiarActivo(
            Long siteId, Long contactId, boolean active) {
        return client.put().uri("/api/v1/sites/{siteId}/contacts/{contactId}/active", siteId, contactId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetActiveRequest(active))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteContactResponse>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<List<CameraResponse>>> listarCamaras() {
        return client.get().uri("/api/v1/cameras")
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<CameraResponse>>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<List<SiteContactResponse>>> listarContactos(Long siteId) {
        return client.get().uri("/api/v1/sites/{siteId}/contacts", siteId)
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<SiteContactResponse>>>() {})
                .returnResult();
    }

    /** Alta de obra que exige 201 — solo preparación, no se mide. */
    private Long crearObraOk(String code, String name) {
        EntityExchangeResult<ApiEnvelope<SiteResponse>> resultado = crearObra(code, name);
        assertThat(resultado.getStatus()).isEqualTo(HttpStatus.CREATED);
        return resultado.getResponseBody().data().id();
    }

    /** Alta de zona que exige 201 — solo preparación, no se mide. */
    private Long crearZonaOk(Long siteId, String code, String name) {
        EntityExchangeResult<ApiEnvelope<ZoneResponse>> resultado = crearZona(siteId, code, name);
        assertThat(resultado.getStatus()).isEqualTo(HttpStatus.CREATED);
        return resultado.getResponseBody().data().id();
    }

    /** Alta de contacto que exige 201 — solo preparación, no se mide. */
    private Long crearContactoOk(Long siteId, String name, String phone, String telegramChatId) {
        EntityExchangeResult<ApiEnvelope<SiteContactResponse>> resultado =
                crearContacto(siteId, name, phone, telegramChatId);
        assertThat(resultado.getStatus()).isEqualTo(HttpStatus.CREATED);
        return resultado.getResponseBody().data().id();
    }

    // ── Medición ──────────────────────────────────────────────────────────

    /**
     * Ejecuta las llamadas de calentamiento (no medidas) y después las llamadas
     * medidas, todas secuenciales y cronometradas con {@code System.nanoTime()}
     * alrededor de la petición completa hasta recibir el cuerpo entero.
     * Comprueba el status esperado en CADA llamada, medidas y de calentamiento.
     */
    private Estadisticas medir(String operacion, HttpStatus esperado, Peticion peticion) {
        for (int i = 0; i < LLAMADAS_CALIENTAMIENTO; i++) {
            Llamada llamada = cronometrar(peticion);
            assertThat(llamada.status())
                    .as("%s — calentamiento %d/%d", operacion, i + 1, LLAMADAS_CALIENTAMIENTO)
                    .isEqualTo(esperado.value());
        }
        List<Double> tiemposMs = new ArrayList<>(LLAMADAS_MEDIDAS);
        for (int i = 0; i < LLAMADAS_MEDIDAS; i++) {
            Llamada llamada = cronometrar(peticion);
            assertThat(llamada.status())
                    .as("%s — llamada medida %d/%d", operacion, i + 1, LLAMADAS_MEDIDAS)
                    .isEqualTo(esperado.value());
            tiemposMs.add(llamada.ms());
        }
        return new Estadisticas(operacion, tiemposMs);
    }

    /** Dispara la petición (leyendo el cuerpo entero) y devuelve status + tiempo de pared. */
    private Llamada cronometrar(Peticion peticion) {
        long inicio = System.nanoTime();
        EntityExchangeResult<?> resultado = peticion.ejecutar();
        double ms = (System.nanoTime() - inicio) / 1_000_000.0;
        return new Llamada(resultado.getStatus().value(), ms);
    }

    private static String formatoMs(double ms) {
        return String.format(Locale.ROOT, "%.1f ms", ms);
    }

    /** Una petición HTTP completa lista para cronometrar (lee el cuerpo entero). */
    @FunctionalInterface
    private interface Peticion {
        EntityExchangeResult<?> ejecutar();
    }

    /** Status HTTP y tiempo de pared (ms) de una llamada cronometrada. */
    private record Llamada(int status, double ms) {}

    /**
     * Estadísticas de latencia (ms) de una operación sobre su muestra medida.
     * Percentiles por rango más cercano: se ordena la muestra y se toma el
     * elemento en el índice {@code ceil(p * N) - 1} (con N = 50: P50 → índice 24,
     * P95 → índice 47).
     */
    private record Estadisticas(String operacion, List<Double> tiemposMs) {

        private int n() {
            return tiemposMs.size();
        }

        private double minimo() {
            return Collections.min(tiemposMs);
        }

        private double p50() {
            return percentil(0.50);
        }

        private double p95() {
            return percentil(0.95);
        }

        private double maximo() {
            return Collections.max(tiemposMs);
        }

        private double percentil(double p) {
            List<Double> ordenados = new ArrayList<>(tiemposMs);
            ordenados.sort(Double::compare);
            return ordenados.get((int) Math.ceil(p * ordenados.size()) - 1);
        }
    }
}
