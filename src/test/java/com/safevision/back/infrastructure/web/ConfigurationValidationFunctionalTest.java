package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.CameraConfigPublisherPort;
import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.SiteContactRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.application.ports.out.ZoneRepositoryPort;
import com.safevision.back.application.service.CameraService;
import com.safevision.back.application.service.SiteContactService;
import com.safevision.back.application.service.SiteService;
import com.safevision.back.application.service.ZoneService;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * CP41/CP42 (HU08) — validación de la configuración del sistema en la capa web,
 * con servicios REALES y repositorios mockeados (solo CameraService recibe un
 * publisher mockeado porque nunca llega a notificar en estos escenarios).
 *
 * <p>Cada controller se monta con WebTestClient.bindToController +
 * {@link GlobalExceptionHandler} (para que el cuerpo de error use el sobre
 * estándar de la API) y el filtro que deja el atributo "username" del exchange
 * — igual que en los *ControllerTest, ya que bindToController no pasa por el
 * filtro JWT real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigurationValidationFunctionalTest — validación de configuración del sistema (HU08)")
class ConfigurationValidationFunctionalTest {

    /** El filtro JWT real deja el username como atributo del exchange (ver SecurityConfig). */
    private static final WebFilter USERNAME_FILTER = (exchange, chain) -> {
        exchange.getAttributes().put("username", "supervisor1");
        return chain.filter(exchange);
    };

    @Mock private SiteRepositoryPort siteRepository;
    @Mock private ZoneRepositoryPort zoneRepository;
    @Mock private SiteContactRepositoryPort siteContactRepository;
    @Mock private CameraRepositoryPort cameraRepository;

    private CameraConfigPublisherPort cameraPublisher;
    private WebTestClient siteClient;
    private WebTestClient zoneClient;
    private WebTestClient contactClient;
    private WebTestClient cameraClient;

    @BeforeEach
    void setUp() {
        cameraPublisher = mock(CameraConfigPublisherPort.class);

        siteClient = WebTestClient.bindToController(new SiteController(new SiteService(siteRepository)))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(USERNAME_FILTER)
                .build();
        zoneClient = WebTestClient.bindToController(new ZoneController(new ZoneService(zoneRepository)))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(USERNAME_FILTER)
                .build();
        contactClient = WebTestClient.bindToController(
                        new SiteContactController(new SiteContactService(siteContactRepository)))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(USERNAME_FILTER)
                .build();
        cameraClient = WebTestClient.bindToController(
                        new CameraController(new CameraService(cameraRepository, cameraPublisher)))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(USERNAME_FILTER)
                .build();
    }

    // ── CP41 — campos vacíos o ausentes ───────────────────────────────

    @Test
    @Tag("CP41")
    @DisplayName("CP41 — campos vacíos o ausentes responden 400 VALIDATION_ERROR sin invocar al repositorio")
    void cp41_camposVaciosOAusentes_retornan400ValidacionError() {
        System.out.println("[CP41] Altas con campos vacíos o ausentes — obras, zonas, contactos y cámaras");

        String sitioVacio = "{\"code\":\"OBRA-41\",\"name\":\"\",\"location\":\"Lima\"}";
        System.out.println("  POST /sites            | name = \"\"     | payload = " + sitioVacio);
        expectValidationRejected(siteClient, "/sites", sitioVacio);

        String sitioSoloEspacios = "{\"code\":\"OBRA-42\",\"name\":\"   \",\"location\":\"Lima\"}";
        System.out.println("  POST /sites            | name = \"   \"  | payload = " + sitioSoloEspacios);
        expectValidationRejected(siteClient, "/sites", sitioSoloEspacios);

        String zonaSinNombre = "{\"code\":\"Z-41\",\"name\":\"\"}";
        System.out.println("  POST /sites/1/zones    | name = \"\"     | payload = " + zonaSinNombre);
        expectValidationRejected(zoneClient, "/sites/1/zones", zonaSinNombre);

        String contactoSinNombre = "{\"name\":\"\",\"phone\":\"999888777\"}";
        System.out.println("  POST /sites/1/contacts | name = \"\"     | payload = " + contactoSinNombre);
        expectValidationRejected(contactClient, "/sites/1/contacts", contactoSinNombre);

        String contactoSinTelefono = "{\"name\":\"Supervisor\",\"phone\":\"\"}";
        System.out.println("  POST /sites/1/contacts | phone = \"\"    | payload = " + contactoSinTelefono);
        expectValidationRejected(contactClient, "/sites/1/contacts", contactoSinTelefono);

        String camaraSinObra = "{\"code\":\"CAM-41\",\"name\":\"Entrada\","
                + "\"ipAddress\":\"127.0.0.1\",\"rtspUrl\":\"rtsp://10.0.0.41/stream\"}";
        System.out.println("  POST /cameras          | siteId ausente | payload = " + camaraSinObra);
        expectValidationRejected(cameraClient, "/cameras", camaraSinObra);

        verifyNoInteractions(siteRepository, zoneRepository, siteContactRepository, cameraRepository,
                cameraPublisher);
        System.out.println("[CP41] Las 6 altas respondieron 400 VALIDATION_ERROR y ningún repositorio "
                + "ni el publisher de config fueron invocados => PASA");
    }

    // ── CP41 — teléfono del supervisor ────────────────────────────────

    @Test
    @Tag("CP41")
    @DisplayName("CP41 — teléfono del supervisor: formatos inválidos 400 y borde de 20 caracteres 201")
    void cp41_telefonoDelSupervisor_respetaFormatoYLongitud() {
        System.out.println("[CP41] Teléfono del supervisor en POST /sites/1/contacts");

        String conLetras = "abc1234567";
        System.out.println("  caso 1 | phone = \"" + conLetras + "\" (" + conLetras.length()
                + " caracteres, contiene letras)");
        expectValidationRejected(contactClient, "/sites/1/contacts", contactPayload(conLetras));

        // El '+' cuenta dentro del máximo de 20 caracteres de la columna site_contacts.phone
        // (VARCHAR(20)) — ver @Pattern de SiteContactRequest — y por eso
        // "+12345678901234567890" (20 dígitos más el '+') se rechaza.
        String veintiUnCaracteresConMas = "+12345678901234567890";
        System.out.println("  caso 2 | phone = \"" + veintiUnCaracteresConMas + "\" ("
                + veintiUnCaracteresConMas.length() + " caracteres con '+' incluido, supera el máximo de 20)");
        expectValidationRejected(contactClient, "/sites/1/contacts", contactPayload(veintiUnCaracteresConMas));

        String veintiUnDigitos = "123456789012345678901";
        System.out.println("  caso 3 | phone = \"" + veintiUnDigitos + "\" (" + veintiUnDigitos.length()
                + " caracteres, supera el máximo de 20)");
        expectValidationRejected(contactClient, "/sites/1/contacts", contactPayload(veintiUnDigitos));

        String veinteDigitos = "12345678901234567890";
        System.out.println("  caso 4 | phone = \"" + veinteDigitos + "\" (" + veinteDigitos.length()
                + " caracteres, borde válido)");
        when(siteContactRepository.save(any(SiteContact.class)))
                .thenAnswer(inv -> Mono.just(contactWithId(inv.getArgument(0), 77L)));

        contactClient.post().uri("/sites/1/contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(contactPayload(veinteDigitos))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteContactResponse>>() {})
                .value(envelope -> {
                    System.out.println("       HTTP status  = " + envelope.status());
                    System.out.println("       data.id      = "
                            + (envelope.data() == null ? null : envelope.data().id()));
                    System.out.println("       data.phone   = "
                            + (envelope.data() == null ? null : envelope.data().phone()));
                    assertThat(envelope.error()).isFalse();
                    assertThat(envelope.data()).isNotNull();
                    assertThat(envelope.data().phone()).isEqualTo(veinteDigitos);
                });

        verify(siteContactRepository, times(1)).save(any(SiteContact.class));
        System.out.println("[CP41] Los teléfonos de " + conLetras.length() + ", "
                + veintiUnCaracteresConMas.length() + " y " + veintiUnDigitos.length()
                + " caracteres (letras, 21 con '+', 21 dígitos) respondieron 400 VALIDATION_ERROR; "
                + "el de " + veinteDigitos.length() + " caracteres creó el contacto con 201 => PASA");
    }

    // ── CP42 — cámara duplicada ───────────────────────────────────────

    @Test
    @Tag("CP42")
    @DisplayName("CP42 — alta de cámara con código duplicado responde 409 CONFLICT sin guardar")
    void cp42_camaraDuplicada_retorna409ConflictSinGuardar() {
        String payload = "{\"siteId\":1,\"zoneId\":10,\"code\":\"CAM-41\",\"name\":\"Entrada\","
                + "\"ipAddress\":\"127.0.0.1\",\"rtspUrl\":\"rtsp://10.0.0.41/stream\"}";
        when(cameraRepository.existsByCode("CAM-41")).thenReturn(Mono.just(true));
        System.out.println("[CP42] POST /cameras con código ya registrado (CAM-41) | payload = " + payload);

        cameraClient.post().uri("/cameras")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {})
                .value(envelope -> {
                    System.out.println("       HTTP status  = " + envelope.status());
                    System.out.println("       errorCode    = " + envelope.errorCode());
                    System.out.println("       descripcion  = " + envelope.errorDescription());
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("CONFLICT");
                });

        verify(cameraRepository, never()).save(any(Camera.class));
        System.out.println("[CP42] 409 con errorCode=CONFLICT y save nunca invocado => PASA");
    }

    // ── Utilidades ────────────────────────────────────────────────────

    /** POST que debe ser rechazado por validación de request: 400 + VALIDATION_ERROR. */
    private void expectValidationRejected(WebTestClient client, String uri, String payload) {
        client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {})
                .value(envelope -> {
                    System.out.println("       HTTP status  = " + envelope.status());
                    System.out.println("       errorCode    = " + envelope.errorCode());
                    System.out.println("       descripcion  = " + envelope.errorDescription());
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("VALIDATION_ERROR");
                });
    }

    /** Payload de alta de contacto con el teléfono indicado. */
    private static String contactPayload(String phone) {
        return "{\"name\":\"Supervisor\",\"phone\":\"" + phone + "\"}";
    }

    /** Devuelve el contacto recibido con el id asignado, como lo haría la base de datos. */
    private static SiteContact contactWithId(SiteContact contact, Long id) {
        return new SiteContact(id, contact.siteId(), contact.name(), contact.phone(),
                contact.telegramChatId(), contact.telegramLinkCode(), contact.active(),
                contact.createdAt(), contact.createdBy(), contact.updatedAt(), contact.updatedBy());
    }
}
