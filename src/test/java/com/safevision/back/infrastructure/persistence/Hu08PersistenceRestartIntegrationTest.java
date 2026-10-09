package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.SafevisionApplication;
import com.safevision.back.infrastructure.security.JwtService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.web.dto.CameraResponse;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import com.safevision.back.infrastructure.web.dto.SiteResponse;
import com.safevision.back.infrastructure.web.dto.ZoneRequest;
import com.safevision.back.infrastructure.web.dto.ZoneResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * HU08 CA1 / CP39 — demuestra el ciclo completo de persistencia: registrar
 * obra + zona + cámara + contacto del supervisor con una instancia del
 * backend, consultarlos por los listados, apagar esa instancia por completo
 * y leer los MISMOS datos desde una segunda instancia nueva apuntando a la
 * MISMA base PostgreSQL (Testcontainers).
 *
 * <p>No usa {@code @SpringBootTest}: cada "reinicio" es un
 * {@code ConfigurableApplicationContext} aparte, arrancado a mano con
 * {@link SpringApplicationBuilder} y cerrado con {@code close()} — un único
 * contexto compartido por ambas fases no podría probar que los datos
 * sobreviven al apagado de la instancia.
 */
@Testcontainers
class Hu08PersistenceRestartIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void initSchema() throws Exception {
        // docs/init-schema.sql es la fuente única de verdad del schema (ver
        // AGENTS.md) — se ejecuta tal cual contra el contenedor, sin copiar
        // una versión paralela a test/resources (mismo criterio que
        // IncidentRepositoryIntegrationTest).
        String schema = Files.readString(Path.of("docs/init-schema.sql"));
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = conn.createStatement()) {
            statement.execute(schema);
        }
    }

    @Test
    @Tag("CP39")
    @DisplayName("CP39 — obra, zona, cámara y supervisor se registran, se consultan y persisten tras reiniciar el backend")
    void cp39_registrarConsultarYPersistirTrasReiniciarElBackend() {
        System.out.println(
                "\n[CP39] obra, zona, cámara y supervisor se registran, se consultan y persisten tras reiniciar el backend");

        // Datos únicos por ejecución: sites.name es UNIQUE global y
        // sites.code/zones.code/cameras.code también, así que el sufijo con
        // nanoTime evita colisiones entre corridas.
        long sufijo = System.nanoTime();
        String siteCode = "CP39-SITE-" + sufijo;
        String siteName = "Obra CP39 " + sufijo;
        String siteLocation = "Av. Los Pinos 123, Lima";
        String zoneCode = "CP39-ZONE-" + sufijo;
        String zoneName = "Piso 2 CP39";
        String cameraCode = "CP39-CAM-" + sufijo;
        String cameraName = "Camara CP39";
        String cameraIp = "127.0.0.1";
        String cameraRtsp = "rtsp://127.0.0.1:554/stream/cp39";
        String contactName = "Supervisor CP39";
        String contactPhone = "+51 999 999 999";

        // ids devueltos por la instancia A — la fase 2 los reutiliza para
        // releerlos desde la instancia B.
        Long siteId = null;
        Long zoneId = null;
        Long cameraId = null;
        Long contactId = null;

        // ===== Fase 1: instancia A — altas por API + verificación de listados =====
        ConfigurableApplicationContext contextoA = arrancarContexto();
        try {
            WebTestClient clientA = clientDe(contextoA);
            String jwtA = tokenDe(contextoA);

            // Altas — cada una debe responder 201 Created (expectStatus).
            SiteResponse obraA = datosDe(crearObra(clientA, jwtA,
                    new SiteRequest(siteCode, siteName, siteLocation)));
            siteId = obraA.id();

            ZoneResponse zonaA = datosDe(crearZona(clientA, jwtA, siteId,
                    new ZoneRequest(zoneCode, zoneName)));
            zoneId = zonaA.id();

            // Al crear la cámara con zoneId asignado, el backend intentará
            // notificar su config al CV por HTTP a 127.0.0.1:5001 — es
            // best-effort (fire-and-forget, HttpCameraConfigPublisher), el
            // fallo de conexión se loguea y no afecta el resultado del alta.
            CameraResponse camaraA = datosDe(crearCamara(clientA, jwtA,
                    new CameraRequest(siteId, zoneId, cameraCode, cameraName, cameraIp, cameraRtsp)));
            cameraId = camaraA.id();

            SiteContactResponse contactoA = datosDe(crearContacto(clientA, jwtA, siteId,
                    new SiteContactRequest(contactName, contactPhone, null)));
            contactId = contactoA.id();

            // Cada alta devuelve exactamente lo que se envió.
            assertThat(obraA.code()).isEqualTo(siteCode);
            assertThat(obraA.name()).isEqualTo(siteName);
            assertThat(zonaA.siteId()).isEqualTo(siteId);
            assertThat(zonaA.code()).isEqualTo(zoneCode);
            assertThat(zonaA.name()).isEqualTo(zoneName);
            assertThat(camaraA.siteId()).isEqualTo(siteId);
            assertThat(camaraA.zoneId()).isEqualTo(zoneId);
            assertThat(camaraA.code()).isEqualTo(cameraCode);
            assertThat(camaraA.rtspUrl()).isEqualTo(cameraRtsp);
            assertThat(contactoA.siteId()).isEqualTo(siteId);
            assertThat(contactoA.phone()).isEqualTo(contactPhone);

            // Listados — los registros recién creados aparecen consultados.
            List<SiteResponse> obras = datosDe(listarObras(clientA, jwtA));
            List<ZoneResponse> zonas = datosDe(listarZonas(clientA, jwtA, siteId));
            List<CameraResponse> camaras = datosDe(listarCamaras(clientA, jwtA));
            List<SiteContactResponse> contactos = datosDe(listarContactos(clientA, jwtA, siteId));

            assertThat(obras).extracting(SiteResponse::id).contains(siteId);
            assertThat(zonas).extracting(ZoneResponse::id).contains(zoneId);
            assertThat(camaras).extracting(CameraResponse::id).contains(cameraId);
            assertThat(contactos).extracting(SiteContactResponse::id).contains(contactId);

            System.out.println("       [antes del reinicio] obra    id=" + obraA.id()
                    + " code=" + obraA.code() + " name=" + obraA.name());
            System.out.println("       [antes del reinicio] zona    id=" + zonaA.id()
                    + " code=" + zonaA.code() + " name=" + zonaA.name());
            System.out.println("       [antes del reinicio] cámara  id=" + camaraA.id()
                    + " code=" + camaraA.code() + " rtspUrl=" + camaraA.rtspUrl());
            System.out.println("       [antes del reinicio] contacto id=" + contactoA.id()
                    + " name=" + contactoA.name() + " phone=" + contactoA.phone());
        } finally {
            // Apagado COMPLETO de la instancia A antes de levantar la B.
            contextoA.close();
        }

        // Copias efectivamente finales para usar dentro de lambdas en la fase 2.
        final Long siteIdFinal = siteId;
        final Long zoneIdFinal = zoneId;
        final Long cameraIdFinal = cameraId;
        final Long contactIdFinal = contactId;

        // ===== Fase 2: instancia B nueva contra la MISMA base =====
        ConfigurableApplicationContext contextoB = arrancarContexto();
        try {
            WebTestClient clientB = clientDe(contextoB);
            String jwtB = tokenDe(contextoB);

            // Lecturas desde la instancia recién levantada — la zona y el
            // contacto no tienen GET individual, se buscan en su listado.
            SiteResponse obraB = datosDe(obtenerObra(clientB, jwtB, siteIdFinal));
            CameraResponse camaraB = datosDe(obtenerCamara(clientB, jwtB, cameraIdFinal));
            ZoneResponse zonaB = datosDe(listarZonas(clientB, jwtB, siteIdFinal)).stream()
                    .filter(z -> z.id().equals(zoneIdFinal)).findFirst().orElse(null);
            SiteContactResponse contactoB = datosDe(listarContactos(clientB, jwtB, siteIdFinal)).stream()
                    .filter(c -> c.id().equals(contactIdFinal)).findFirst().orElse(null);

            assertThat(zonaB).isNotNull();
            assertThat(contactoB).isNotNull();

            // Mismos ids y mismos valores que antes del reinicio.
            assertThat(obraB.id()).isEqualTo(siteIdFinal);
            assertThat(obraB.code()).isEqualTo(siteCode);
            assertThat(obraB.name()).isEqualTo(siteName);
            assertThat(obraB.location()).isEqualTo(siteLocation);

            assertThat(zonaB.id()).isEqualTo(zoneIdFinal);
            assertThat(zonaB.siteId()).isEqualTo(siteIdFinal);
            assertThat(zonaB.code()).isEqualTo(zoneCode);
            assertThat(zonaB.name()).isEqualTo(zoneName);

            assertThat(camaraB.id()).isEqualTo(cameraIdFinal);
            assertThat(camaraB.siteId()).isEqualTo(siteIdFinal);
            assertThat(camaraB.zoneId()).isEqualTo(zoneIdFinal);
            assertThat(camaraB.code()).isEqualTo(cameraCode);
            assertThat(camaraB.name()).isEqualTo(cameraName);
            assertThat(camaraB.ipAddress()).isEqualTo(cameraIp);
            assertThat(camaraB.rtspUrl()).isEqualTo(cameraRtsp);

            assertThat(contactoB.id()).isEqualTo(contactIdFinal);
            assertThat(contactoB.siteId()).isEqualTo(siteIdFinal);
            assertThat(contactoB.name()).isEqualTo(contactName);
            assertThat(contactoB.phone()).isEqualTo(contactPhone);

            System.out.println("       [tras el reinicio]   obra    id=" + obraB.id()
                    + " code=" + obraB.code() + " name=" + obraB.name());
            System.out.println("       [tras el reinicio]   zona    id=" + zonaB.id()
                    + " code=" + zonaB.code() + " name=" + zonaB.name());
            System.out.println("       [tras el reinicio]   cámara  id=" + camaraB.id()
                    + " code=" + camaraB.code() + " rtspUrl=" + camaraB.rtspUrl());
            System.out.println("       [tras el reinicio]   contacto id=" + contactoB.id()
                    + " name=" + contactoB.name() + " phone=" + contactoB.phone());
        } finally {
            contextoB.close();
        }

        System.out.println(
                "[CP39] obra, zona, cámara y supervisor sobreviven al reinicio del backend sobre la misma base => PASA");
    }

    /**
     * Levanta una instancia completa del backend contra la base del
     * contenedor. Las propiedades R2DBC y el puerto van como argumentos de
     * línea de comandos (la precedencia más alta del Environment) — con
     * {@code SpringApplicationBuilder.properties()} (menor precedencia)
     * {@code application-dev.yml} las sobrescribiría y apuntaríamos a la base
     * equivocada. {@code --server.port=0} asigna un puerto efímero por
     * instancia, leído después con {@code local.server.port}.
     */
    private static ConfigurableApplicationContext arrancarContexto() {
        return new SpringApplicationBuilder(SafevisionApplication.class)
                .profiles("dev")
                .run(
                        "--spring.r2dbc.url=r2dbc:postgresql://%s:%d/%s".formatted(
                                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()),
                        "--spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "--spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "--server.port=0");
    }

    /** Cliente HTTP contra el servidor real de la instancia (bindToServer + base-path /api/v1). */
    private static WebTestClient clientDe(ConfigurableApplicationContext contexto) {
        String baseUrl = "http://127.0.0.1:"
                + contexto.getEnvironment().getRequiredProperty("local.server.port");
        return WebTestClient.bindToServer().baseUrl(baseUrl).build();
    }

    /** JWT firmado con el secreto real de la instancia (bean JwtService del propio contexto). */
    private static String tokenDe(ConfigurableApplicationContext contexto) {
        return contexto.getBean(JwtService.class).generateToken("admin", "ADMIN");
    }

    /** Extrae el {@code data} del sobre, comprobando con AssertJ que la respuesta fue un éxito. */
    private static <T> T datosDe(ApiEnvelope<T> envelope) {
        assertThat(envelope).isNotNull();
        assertThat(envelope.error()).isFalse();
        assertThat(envelope.data()).isNotNull();
        return envelope.data();
    }

    private static ApiEnvelope<SiteResponse> crearObra(WebTestClient client, String jwt, SiteRequest request) {
        return client.post().uri("/sites")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<List<SiteResponse>> listarObras(WebTestClient client, String jwt) {
        return client.get().uri("/sites")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<SiteResponse>>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<SiteResponse> obtenerObra(WebTestClient client, String jwt, Long siteId) {
        return client.get().uri("/sites/{id}", siteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<ZoneResponse> crearZona(WebTestClient client, String jwt, Long siteId,
            ZoneRequest request) {
        return client.post().uri("/sites/{siteId}/zones", siteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<ZoneResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<List<ZoneResponse>> listarZonas(WebTestClient client, String jwt, Long siteId) {
        return client.get().uri("/sites/{siteId}/zones", siteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<ZoneResponse>>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<CameraResponse> crearCamara(WebTestClient client, String jwt,
            CameraRequest request) {
        return client.post().uri("/cameras")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<CameraResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<List<CameraResponse>> listarCamaras(WebTestClient client, String jwt) {
        return client.get().uri("/cameras")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<CameraResponse>>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<CameraResponse> obtenerCamara(WebTestClient client, String jwt, Long cameraId) {
        return client.get().uri("/cameras/{id}", cameraId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<CameraResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<SiteContactResponse> crearContacto(WebTestClient client, String jwt, Long siteId,
            SiteContactRequest request) {
        return client.post().uri("/sites/{siteId}/contacts", siteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteContactResponse>>() {})
                .returnResult()
                .getResponseBody();
    }

    private static ApiEnvelope<List<SiteContactResponse>> listarContactos(WebTestClient client, String jwt,
            Long siteId) {
        return client.get().uri("/sites/{siteId}/contacts", siteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<SiteContactResponse>>>() {})
                .returnResult()
                .getResponseBody();
    }
}
