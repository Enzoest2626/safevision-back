package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceRepositoryPort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.application.ports.out.WorkerRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Evidence;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.infrastructure.web.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.web.dto.CvIncidentMessage;
import com.safevision.back.infrastructure.web.dto.IncidentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentService — registro de incidente, delega notificación")
class IncidentServiceTest {

    @Mock private IncidentRepositoryPort incidentRepo;
    @Mock private EvidenceRepositoryPort evidenceRepo;
    @Mock private WorkerRepositoryPort workerRepo;
    @Mock private CameraRepositoryPort cameraRepo;
    @Mock private SiteRepositoryPort siteRepo;
    @Mock private IncidentNotificationService notificationService;

    private IncidentService service;

    private final Worker worker = new Worker(10L, 1L, 3, "Juan", "Perez", "Albañil",
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Site site = new Site(1L, "OBRA-1", "Main-Site", "Lima", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private IncidentRequest requestFor(String siteName) {
        return new IncidentRequest(3, List.of("helmet", "vest"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", siteName, "ZmFrZS1mcmFtZQ==");
    }

    @BeforeEach
    void setUp() {
        service = new IncidentService(incidentRepo, evidenceRepo, workerRepo, cameraRepo, siteRepo,
                notificationService);

        lenient().when(workerRepo.findByCode(3)).thenReturn(Mono.just(worker));
        lenient().when(cameraRepo.findByCode("CAM-01")).thenReturn(Mono.just(camera));
        lenient().when(siteRepo.findByName("Main-Site")).thenReturn(Mono.just(site));
        lenient().when(incidentRepo.save(any(Incident.class)))
                .thenAnswer(inv -> {
                    Incident i = inv.getArgument(0);
                    return Mono.just(new Incident(100L, i.workerId(), i.cameraId(), i.siteId(), i.externalId(),
                            i.missingEpp(), i.occurredAt(), i.createdAt()));
                });
        lenient().when(evidenceRepo.save(any(Evidence.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(notificationService.notify(any(), any(), any(), any(Evidence.class), anyString())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Incidente válido → persiste incidente + evidencia y delega la notificación")
    void registraIncidente_valido_persisteYDelegaNotificacion() {
        StepVerifier.create(service.register(requestFor("Main-Site"), "trace-test"))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(100L);
                    assertThat(response.workerId()).isEqualTo(10L);
                    assertThat(response.cameraId()).isEqualTo(20L);
                    assertThat(response.siteId()).isEqualTo(1L);
                    assertThat(response.missingEpp()).containsExactly("helmet", "vest");
                })
                .verifyComplete();

        verify(evidenceRepo).save(any(Evidence.class));
        verify(notificationService).notify(any(Incident.class), eq(camera), eq(site), any(Evidence.class), anyString());
    }

    @Test
    @DisplayName("La notificación delegada recibe el incidente con trabajador, EPP y timestamp correctos")
    void registraIncidente_notificacionRecibeDatosCorrectos() {
        LocalDateTime timestampEsperado = LocalDateTime.of(2026, 6, 24, 13, 30);

        StepVerifier.create(service.register(requestFor("Main-Site"), "trace-test"))
                .assertNext(response -> assertThat(response.id()).isEqualTo(100L))
                .verifyComplete();

        ArgumentCaptor<Incident> incidentCaptor = ArgumentCaptor.forClass(Incident.class);
        verify(notificationService).notify(incidentCaptor.capture(), eq(camera), eq(site), any(Evidence.class), anyString());
        Incident incidentEnviado = incidentCaptor.getValue();

        assertThat(incidentEnviado.workerId()).isEqualTo(10L);
        assertThat(incidentEnviado.missingEpp()).containsExactly("helmet", "vest");
        assertThat(incidentEnviado.occurredAt()).isEqualTo(timestampEsperado);
    }

    @Test
    @DisplayName("Worker desconocido → 400 BAD_REQUEST, nada se persiste ni se notifica")
    void registraIncidente_workerDesconocido_lanzaBadRequest() {
        when(workerRepo.findByCode(3)).thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Main-Site"), "trace-test"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(incidentRepo, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), any(Evidence.class), anyString());
    }

    @Test
    @DisplayName("Cámara desconocida → 400 BAD_REQUEST, nada se persiste")
    void registraIncidente_camaraDesconocida_lanzaBadRequest() {
        when(cameraRepo.findByCode("CAM-01")).thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Main-Site"), "trace-test"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(incidentRepo, never()).save(any());
    }

    @Test
    @DisplayName("Obra desconocida → 400 BAD_REQUEST, nada se persiste")
    void registraIncidente_obraDesconocida_lanzaBadRequest() {
        when(siteRepo.findByName("Obra-Fantasma")).thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Obra-Fantasma"), "trace-test"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(incidentRepo, never()).save(any());
    }

    // ── registerFromCv (flujo MQTT/CV) ────────────────────────────────────────

    private CvIncidentMessage cvMessageFor(String siteName) {
        return new CvIncidentMessage("cv-uuid-1", 3, List.of("helmet", "vest"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", siteName,
                "incidents/2026-08-10/cv-uuid-1/photo.jpg");
    }

    @Test
    @DisplayName("registerFromCv: mensaje valido -> persiste incidente con external_id y evidencia con storageKey")
    void registerFromCv_valido_persisteConExternalIdYStorageKey() {
        StepVerifier.create(service.registerFromCv(cvMessageFor("Main-Site"), "trace-mqtt"))
                .assertNext(response -> assertThat(response.id()).isEqualTo(100L))
                .verifyComplete();

        ArgumentCaptor<Incident> incidentCaptor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepo).save(incidentCaptor.capture());
        assertThat(incidentCaptor.getValue().externalId()).isEqualTo("cv-uuid-1");

        ArgumentCaptor<Evidence> evidenceCaptor = ArgumentCaptor.forClass(Evidence.class);
        verify(evidenceRepo).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().evidenceType()).isEqualTo("PHOTO");
        assertThat(evidenceCaptor.getValue().storageKey()).isEqualTo("incidents/2026-08-10/cv-uuid-1/photo.jpg");
        assertThat(evidenceCaptor.getValue().frameB64()).isNull();

        verify(notificationService).notify(any(Incident.class), eq(camera), eq(site), any(Evidence.class), eq("trace-mqtt"));
    }

    @Test
    @DisplayName("registerFromCv: worker desconocido -> 400 BAD_REQUEST, nada se persiste")
    void registerFromCv_workerDesconocido_lanzaBadRequest() {
        when(workerRepo.findByCode(3)).thenReturn(Mono.empty());

        StepVerifier.create(service.registerFromCv(cvMessageFor("Main-Site"), "trace-mqtt"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(incidentRepo, never()).save(any());
    }

    @Test
    @DisplayName("registerFromCv: obra desconocida -> 400 BAD_REQUEST, nada se persiste")
    void registerFromCv_obraDesconocida_lanzaBadRequest() {
        when(siteRepo.findByName("Obra-Fantasma")).thenReturn(Mono.empty());

        StepVerifier.create(service.registerFromCv(cvMessageFor("Obra-Fantasma"), "trace-mqtt"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(incidentRepo, never()).save(any());
    }

    // ── registerClipReady (flujo MQTT/CV, clip de video) ──────────────────────

    @Test
    @DisplayName("registerClipReady: incidente existente -> guarda evidencia tipo VIDEO, no notifica")
    void registerClipReady_incidenteExistente_guardaVideoSinNotificar() {
        Incident existing = new Incident(100L, 10L, 20L, 1L, "cv-uuid-1",
                new String[]{"helmet"}, LocalDateTime.now(), LocalDateTime.now());
        when(incidentRepo.findByExternalId("cv-uuid-1")).thenReturn(Mono.just(existing));
        CvClipReadyMessage clipMessage = new CvClipReadyMessage("cv-uuid-1",
                "incidents/2026-08-10/cv-uuid-1/clip.mp4", 10.0, 4831201L);

        StepVerifier.create(service.registerClipReady(clipMessage))
                .verifyComplete();

        ArgumentCaptor<Evidence> evidenceCaptor = ArgumentCaptor.forClass(Evidence.class);
        verify(evidenceRepo).save(evidenceCaptor.capture());
        Evidence saved = evidenceCaptor.getValue();
        assertThat(saved.incidentId()).isEqualTo(100L);
        assertThat(saved.evidenceType()).isEqualTo("VIDEO");
        assertThat(saved.storageKey()).isEqualTo("incidents/2026-08-10/cv-uuid-1/clip.mp4");
        assertThat(saved.durationSeconds()).isEqualTo(10.0);
        assertThat(saved.fileSizeBytes()).isEqualTo(4831201L);

        verify(notificationService, never()).notify(any(), any(), any(), any(Evidence.class), anyString());
    }

    @Test
    @DisplayName("registerClipReady: incidente no encontrado por external_id -> 400 BAD_REQUEST, no guarda evidencia")
    void registerClipReady_incidenteNoEncontrado_lanzaBadRequest() {
        when(incidentRepo.findByExternalId("desconocido")).thenReturn(Mono.empty());
        CvClipReadyMessage clipMessage = new CvClipReadyMessage("desconocido", "x/clip.mp4", 10.0, 100L);

        StepVerifier.create(service.registerClipReady(clipMessage))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(evidenceRepo, never()).save(any());
    }
}
