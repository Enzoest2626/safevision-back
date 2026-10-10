package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.infrastructure.security.JwtService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.web.dto.CameraResponse;
import com.safevision.back.infrastructure.web.dto.SetActiveRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import com.safevision.back.infrastructure.web.dto.SiteResponse;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * TA38 (HU08) — pruebas de integración de la API real contra PostgreSQL real
 * (Testcontainers): CP42 (alta duplicada o con referencia inexistente), CP43
 * (solo los supervisores activos con Chat ID reciben alertas) y CP44 (pausar y
 * reactivar un supervisor sin perder su vínculo de Telegram).
 *
 * <p>Se usa un servidor real ({@code RANDOM_PORT}) y no
 * {@code bindToApplicationContext}: {@code spring.webflux.base-path=/api/v1}
 * se aplica envolviendo el {@code HttpHandler}, así que solo un servidor de
 * verdad monta el prefijo — por eso todas las rutas de estos tests son
 * {@code /api/v1/...}.
 *
 * <p>La autenticación usa el bean real {@link JwtService} (mismo secreto que
 * valida {@code SecurityConfig.jwtAuthFilter}) — sin login ni credenciales
 * reales. Requiere Docker encendido (Testcontainers levanta el Postgres).
 *
 * <p>Supera el límite de 150 líneas por agrupar los seis escenarios de
 * CP42/CP43/CP44 en un solo contexto de Spring + contenedor: cada escenario
 * solo son unas pocas líneas de helpers compartidos.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // src/test/resources/application.yml se antepone al application.yml
        // principal (el que define spring.webflux.base-path), así que el prefijo
        // se declara explícitamente para que el servidor real lo monte — mismo
        // valor que producción, sin cambiar ningún comportamiento.
        properties = "spring.webflux.base-path=/api/v1")
@Testcontainers
// src/test/resources/application.yml (usado por el resto de los tests, sin
// perfil activo) no trae s3.*/telegram.* — hacen falta para levantar el
// contexto completo. "dev" sí las trae, con sus mismos defaults inofensivos.
@ActiveProfiles("dev")
@DisplayName("Hu08ConfigurationIntegrationTest — CP42/CP43/CP44 contra Postgres real (TA38, HU08)")
class Hu08ConfigurationIntegrationTest {

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
        // docs/init-schema.sql es la fuente única de verdad del schema (ver
        // CLAUDE.md) — se ejecuta tal cual contra el contenedor, sin copiar
        // una versión paralela a test/resources.
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

    /** Implementa {@code SiteContactRepositoryPort.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue}. */
    @Autowired
    private SiteContactRepository siteContactRepository;

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

    // ── CP42 — alta duplicada o con referencia inexistente ────────────────

    @Test
    @Tag("CP42")
    @DisplayName("CP42 — alta duplicada en base de datos (mismo nombre) responde 409 y nunca 500")
    void cp42_altaDuplicadaEnBaseDeDatos_responde409Conflict() {
        System.out.println("\n[CP42] Alta duplicada en base de datos — obra con el mismo nombre (HU08):");
        String sufijo = Long.toString(System.nanoTime());
        String nombre = "Obra CP42 " + sufijo;
        String codePrimera = "SITE-CP42A-" + sufijo;
        String codeDuplicada = "SITE-CP42B-" + sufijo;

        EntityExchangeResult<ApiEnvelope<SiteResponse>> primera = crearObra(codePrimera, nombre);
        // Mismo nombre con código distinto: el service solo pre-valida el código,
        // así que la colisión la detecta el UNIQUE(name) de sites.
        EntityExchangeResult<ApiEnvelope<SiteResponse>> duplicada = crearObra(codeDuplicada, nombre);

        System.out.println("       obra 1: code=" + codePrimera + " name=" + nombre
                + " -> HTTP " + primera.getStatus().value());
        System.out.println("       obra 2: code=" + codeDuplicada + " name=" + nombre
                + " -> HTTP " + duplicada.getStatus().value()
                + " errorCode=" + duplicada.getResponseBody().errorCode());

        assertThat(primera.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(primera.getResponseBody().error()).isFalse();
        assertThat(primera.getResponseBody().data()).isNotNull();

        assertThat(duplicada.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicada.getStatus()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(duplicada.getResponseBody().error()).isTrue();
        assertThat(duplicada.getResponseBody().errorCode()).isEqualTo("CONFLICT");

        System.out.println("[CP42] Alta duplicada responde 409 CONFLICT (no 500) => PASA");
    }

    @Test
    @Tag("CP42")
    @DisplayName("CP42 — referencia inexistente (siteId 999999) responde 400 y nunca 500")
    void cp42_referenciaInexistente_responde400BadRequest() {
        System.out.println("\n[CP42] Alta con referencia inexistente — siteId 999999 (HU08):");
        String sufijo = Long.toString(System.nanoTime());

        EntityExchangeResult<ApiEnvelope<CameraResponse>> camara =
                crearCamara(999_999L, "CAM-CP42-" + sufijo);
        EntityExchangeResult<ApiEnvelope<SiteContactResponse>> contacto =
                crearContacto(999_999L, "Contacto CP42 " + sufijo, "+51 999 000 000", "9000" + sufijo);

        System.out.println("       POST /api/v1/cameras siteId=999999        -> HTTP "
                + camara.getStatus().value() + " errorCode=" + camara.getResponseBody().errorCode());
        System.out.println("       POST /api/v1/sites/999999/contacts        -> HTTP "
                + contacto.getStatus().value() + " errorCode=" + contacto.getResponseBody().errorCode());

        // La FK de site_id se viola en la base (23503) y se traduce a 400, no a 500.
        assertThat(camara.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(camara.getStatus()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(camara.getResponseBody().error()).isTrue();
        assertThat(camara.getResponseBody().errorCode()).isEqualTo("BAD_REQUEST");

        assertThat(contacto.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(contacto.getStatus()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(contacto.getResponseBody().error()).isTrue();
        assertThat(contacto.getResponseBody().errorCode()).isEqualTo("BAD_REQUEST");

        System.out.println("[CP42] Referencia inexistente responde 400 BAD_REQUEST (no 500) => PASA");
    }

    @Test
    @Tag("CP42")
    @DisplayName("CP42 — altas simultáneas con el mismo código: exactamente un 201 y un 409 por ronda")
    void cp42_altasSimultaneasMismoCodigo_un201Un409Sin500() throws Exception {
        System.out.println("\n[CP42] Altas simultáneas de cámara con el mismo código — 5 rondas (HU08):");
        String sufijo = Long.toString(System.nanoTime());
        Long siteId = crearObraOk("SITE-CP42C-" + sufijo, "Obra CP42c " + sufijo);

        List<String> codigos = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int ronda = 1; ronda <= 5; ronda++) {
                String code = "CAM-CP42-" + sufijo + "-" + ronda;
                codigos.add(code);
                List<RespuestaHttp> respuestas = dispararDosAltasSimultaneas(pool, siteId, code);
                System.out.println("       ronda " + ronda + " | code=" + code
                        + " | petición 1: " + respuestas.get(0).status()
                        + " | petición 2: " + respuestas.get(1).status());

                List<Integer> statuses = respuestas.stream().map(RespuestaHttp::status).sorted().toList();
                assertThat(statuses).doesNotContain(500);
                assertThat(statuses).containsExactlyInAnyOrder(201, 409);
                RespuestaHttp rechazada = respuestas.stream()
                        .filter(r -> r.status() == 409).findFirst().orElseThrow();
                assertThat(rechazada.envelope().status()).isEqualTo("409");
                assertThat(rechazada.envelope().error()).isTrue();
                assertThat(rechazada.envelope().errorCode()).isEqualTo("CONFLICT");
            }
        } finally {
            pool.shutdownNow();
        }

        // De cada código debe existir una sola cámara — el UNIQUE de la base no
        // deja que el perdedor de la carrera inserte nada.
        List<CameraResponse> camaras = listarCamaras();
        for (String code : codigos) {
            long cantidad = camaras.stream().filter(c -> code.equals(c.code())).count();
            System.out.println("       cámaras con code=" + code + " en GET /api/v1/cameras = " + cantidad);
            assertThat(cantidad).isEqualTo(1);
        }

        System.out.println("[CP42] Altas simultáneas: un 201 y un 409 por ronda, una sola cámara por código => PASA");
    }

    // ── CP43 — solo supervisores activos con Chat ID reciben alertas ──────

    @Test
    @Tag("CP43")
    @DisplayName("CP43 — solo los supervisores activos con Chat ID aparecen como destinatarios")
    void cp43_soloSupervisoresActivosConChatId_recibenAlertas() {
        System.out.println("\n[CP43] Destinatarios de alertas — solo contactos activos con Chat ID (HU08):");
        String sufijo = Long.toString(System.nanoTime());
        Long siteId = crearObraOk("SITE-CP43-" + sufijo, "Obra CP43 " + sufijo);

        // 1. Activo con Chat ID — el único que debe recibir alertas.
        Long idConChat = crearContactoOk(siteId, "Supervisor con chat " + sufijo,
                "+51 999 111 111", "1000001");
        // 2. Activo SIN Chat ID — no hay a quién enviarle la alerta.
        Long idSinChat = crearContactoOk(siteId, "Supervisor sin chat " + sufijo,
                "+51 999 222 222", null);
        // 3. Con Chat ID pero pausado — no recibe alertas hasta reactivarse.
        Long idPausado = crearContactoOk(siteId, "Supervisor pausado " + sufijo,
                "+51 999 333 333", "3000003");
        EntityExchangeResult<ApiEnvelope<SiteContactResponse>> pausado =
                cambiarActivo(siteId, idPausado, false);
        assertThat(pausado.getStatus()).isEqualTo(HttpStatus.OK);

        List<SiteContact> destinatarios = siteContactRepository
                .findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(siteId)
                .collectList().block();

        System.out.println("       siteId                              = " + siteId);
        System.out.println("       contacto 1 (activo + chatId)        = id " + idConChat + " chatId 1000001");
        System.out.println("       contacto 2 (activo SIN chatId)      = id " + idSinChat);
        System.out.println("       contacto 3 (pausado con chatId)     = id " + idPausado
                + " chatId 3000003 -> PUT active HTTP " + pausado.getStatus().value());
        System.out.println("       destinatarios devueltos por la consulta = " + resumen(destinatarios));

        assertThat(destinatarios).hasSize(1);
        assertThat(destinatarios.get(0).id()).isEqualTo(idConChat);
        assertThat(destinatarios.get(0).telegramChatId()).isEqualTo("1000001");
        assertThat(destinatarios).extracting(SiteContact::id).doesNotContain(idSinChat, idPausado);

        System.out.println("[CP43] Solo el supervisor activo con Chat ID aparece como destinatario => PASA");
    }

    // ── CP44 — pausar y reactivar un supervisor ───────────────────────────

    @Test
    @Tag("CP44")
    @DisplayName("CP44 — pausar y reactivar un supervisor sin perder el vínculo de Telegram")
    void cp44_pausarYReactivarSupervisor_conservaVinculoDeTelegram() {
        System.out.println("\n[CP44] Pausar y reactivar un supervisor sin perder el vínculo (HU08):");
        String sufijo = Long.toString(System.nanoTime());
        String chatId = "4000004";
        Long siteId = crearObraOk("SITE-CP44-" + sufijo, "Obra CP44 " + sufijo);
        Long contactId = crearContactoOk(siteId, "Supervisor CP44 " + sufijo,
                "+51 988 444 444", chatId);

        // Pausar: deja de aparecer como destinatario, pero el chatId queda intacto.
        EntityExchangeResult<ApiEnvelope<SiteContactResponse>> pausa =
                cambiarActivo(siteId, contactId, false);
        List<SiteContact> destinatariosPausado = siteContactRepository
                .findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(siteId)
                .collectList().block();
        List<SiteContactResponse> listadoPausado = listarContactos(siteId);

        // Reactivar: vuelve a la lista de destinatarios con el mismo vínculo.
        EntityExchangeResult<ApiEnvelope<SiteContactResponse>> reactivacion =
                cambiarActivo(siteId, contactId, true);
        List<SiteContact> destinatariosActivo = siteContactRepository
                .findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(siteId)
                .collectList().block();
        List<SiteContactResponse> listadoActivo = listarContactos(siteId);

        System.out.println("       siteId=" + siteId + " contactId=" + contactId + " chatId=" + chatId);
        System.out.println("       PUT .../active {false} -> HTTP " + pausa.getStatus().value()
                + " | destinatarios devueltos = " + resumen(destinatariosPausado)
                + " | GET contacts = " + resumenRespuesta(listadoPausado));
        System.out.println("       PUT .../active {true}  -> HTTP " + reactivacion.getStatus().value()
                + " | destinatarios devueltos = " + resumen(destinatariosActivo)
                + " | GET contacts = " + resumenRespuesta(listadoActivo));

        assertThat(pausa.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(destinatariosPausado).isEmpty();
        assertThat(listadoPausado).hasSize(1);
        assertThat(listadoPausado.get(0).id()).isEqualTo(contactId);
        assertThat(listadoPausado.get(0).active()).isFalse();
        assertThat(listadoPausado.get(0).telegramChatId()).isEqualTo(chatId);

        assertThat(reactivacion.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(destinatariosActivo).hasSize(1);
        assertThat(destinatariosActivo.get(0).id()).isEqualTo(contactId);
        assertThat(destinatariosActivo.get(0).telegramChatId()).isEqualTo(chatId);
        assertThat(listadoActivo).hasSize(1);
        assertThat(listadoActivo.get(0).id()).isEqualTo(contactId);
        assertThat(listadoActivo.get(0).active()).isTrue();
        assertThat(listadoActivo.get(0).telegramChatId()).isEqualTo(chatId);

        System.out.println("[CP44] Pausar oculta al supervisor de los destinatarios y reactivar lo restaura con el mismo chatId => PASA");
    }

    // ── Helpers HTTP ──────────────────────────────────────────────────────

    private EntityExchangeResult<ApiEnvelope<SiteResponse>> crearObra(String code, String name) {
        return client.post().uri("/api/v1/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteRequest(code, name, null))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteResponse>>() {})
                .returnResult();
    }

    private EntityExchangeResult<ApiEnvelope<CameraResponse>> crearCamara(Long siteId, String code) {
        return client.post().uri("/api/v1/cameras")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CameraRequest(siteId, null, code, "Camara " + code, null, null))
                .exchange()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<CameraResponse>>() {})
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

    /** Alta de obra que exige 201 — para los escenarios donde solo es el arranque. */
    private Long crearObraOk(String code, String name) {
        EntityExchangeResult<ApiEnvelope<SiteResponse>> resultado = crearObra(code, name);
        assertThat(resultado.getStatus()).isEqualTo(HttpStatus.CREATED);
        return resultado.getResponseBody().data().id();
    }

    /** Alta de contacto que exige 201 — para los escenarios donde solo es el arranque. */
    private Long crearContactoOk(Long siteId, String name, String phone, String telegramChatId) {
        EntityExchangeResult<ApiEnvelope<SiteContactResponse>> resultado =
                crearContacto(siteId, name, phone, telegramChatId);
        assertThat(resultado.getStatus()).isEqualTo(HttpStatus.CREATED);
        return resultado.getResponseBody().data().id();
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

    private List<SiteContactResponse> listarContactos(Long siteId) {
        return client.get().uri("/api/v1/sites/{siteId}/contacts", siteId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<SiteContactResponse>>>() {})
                .returnResult().getResponseBody().data();
    }

    private List<CameraResponse> listarCamaras() {
        return client.get().uri("/api/v1/cameras")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<CameraResponse>>>() {})
                .returnResult().getResponseBody().data();
    }

    /** Dispara DOS POST /api/v1/cameras con el mismo código lo más a la vez posible. */
    private List<RespuestaHttp> dispararDosAltasSimultaneas(ExecutorService pool, Long siteId, String code)
            throws Exception {
        CountDownLatch liberar = new CountDownLatch(1);
        List<Future<RespuestaHttp>> llamados = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            llamados.add(pool.submit(() -> {
                liberar.await();
                EntityExchangeResult<ApiEnvelope<CameraResponse>> resultado = crearCamara(siteId, code);
                return new RespuestaHttp(resultado.getStatus().value(), resultado.getResponseBody());
            }));
        }
        liberar.countDown();
        List<RespuestaHttp> respuestas = new ArrayList<>();
        for (Future<RespuestaHttp> llamado : llamados) {
            respuestas.add(llamado.get(30, TimeUnit.SECONDS));
        }
        return respuestas;
    }

    /** Status HTTP real y sobre devuelto por un POST concurrente de CP42. */
    private record RespuestaHttp(int status, ApiEnvelope<CameraResponse> envelope) {}

    private static String resumen(List<SiteContact> contactos) {
        return contactos.stream()
                .map(c -> "id " + c.id() + " (chatId " + c.telegramChatId() + ", active " + c.active() + ")")
                .toList().toString();
    }

    private static String resumenRespuesta(List<SiteContactResponse> contactos) {
        return contactos.stream()
                .map(c -> "id " + c.id() + " (chatId " + c.telegramChatId() + ", active " + c.active() + ")")
                .toList().toString();
    }
}
