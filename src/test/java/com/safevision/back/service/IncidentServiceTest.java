package com.safevision.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.dto.IncidentRequest;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Evidence;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Site;
import com.safevision.back.model.Worker;
import com.safevision.back.repository.CameraRepository;
import com.safevision.back.repository.EvidenceRepository;
import com.safevision.back.repository.IncidentRepository;
import com.safevision.back.repository.SiteRepository;
import com.safevision.back.repository.WorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentService — HU10 (registro de incidente, delega notificación)")
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepo;
    @Mock private EvidenceRepository evidenceRepo;
    @Mock private WorkerRepository workerRepo;
    @Mock private CameraRepository cameraRepo;
    @Mock private SiteRepository siteRepo;
    @Mock private IncidentNotificationService notificationService;

    private IncidentService service;

    private final Worker worker = new Worker(10L, 1L, 3, "Juan", "Perez", "Albañil",
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Site site = new Site(1L, "Main-Site", "Lima", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private IncidentRequest requestFor(String siteName) {
        return new IncidentRequest(3, List.of("helmet", "vest"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", siteName, "ZmFrZS1mcmFtZQ==");
    }

    @BeforeEach
    void setUp() {
        service = new IncidentService(incidentRepo, evidenceRepo, workerRepo, cameraRepo, siteRepo, notificationService);

        lenient().when(workerRepo.findByCode(3)).thenReturn(Mono.just(worker));
        lenient().when(cameraRepo.findByCode("CAM-01")).thenReturn(Mono.just(camera));
        lenient().when(siteRepo.findByName("Main-Site")).thenReturn(Mono.just(site));
        lenient().when(incidentRepo.save(any(Incident.class)))
                .thenAnswer(inv -> {
                    Incident i = inv.getArgument(0);
                    return Mono.just(new Incident(100L, i.workerId(), i.cameraId(), i.siteId(),
                            i.missingEpp(), i.occurredAt(), i.createdAt()));
                });
        lenient().when(evidenceRepo.save(any(Evidence.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(notificationService.notify(any(), any(), any(), anyString(), anyString())).thenReturn(Mono.empty());
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
        verify(notificationService).notify(any(Incident.class), eq(camera), eq(site), anyString(), anyString());
    }

    @Test
    @DisplayName("La notificación delegada recibe el incidente con trabajador, EPP y timestamp correctos")
    void registraIncidente_notificacionRecibeDatosCorrectos() {
        LocalDateTime timestampEsperado = LocalDateTime.of(2026, 6, 24, 13, 30);

        StepVerifier.create(service.register(requestFor("Main-Site"), "trace-test"))
                .assertNext(response -> assertThat(response.id()).isEqualTo(100L))
                .verifyComplete();

        ArgumentCaptor<Incident> incidentCaptor = ArgumentCaptor.forClass(Incident.class);
        verify(notificationService).notify(incidentCaptor.capture(), eq(camera), eq(site), anyString(), anyString());
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
        verify(notificationService, never()).notify(any(), any(), any(), anyString(), anyString());
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

    @Test
    @DisplayName("Incidente inexistente en GET /{id} → 404 NOT_FOUND")
    void buscarPorId_inexistente_lanzaNotFound() {
        when(incidentRepo.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(999L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("findByFilter sin from/to → usa rango por defecto (2000-01-01 .. ahora)")
    void buscarPorFiltro_sinRango_usaRangoPorDefecto() {
        Incident sample = new Incident(100L, 10L, 20L, 1L,
                new String[]{"helmet"}, LocalDateTime.now(), LocalDateTime.now());
        when(incidentRepo.findByFilter(eq(1L), any(), any(), any())).thenReturn(Flux.just(sample));

        StepVerifier.create(service.findByFilter(1L, null, null, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(incidentRepo).findByFilter(eq(1L), any(), eq(LocalDateTime.of(2000, 1, 1, 0, 0)), any());
    }
}
