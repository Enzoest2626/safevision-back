package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.EvidenceRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceStoragePort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.domain.model.Evidence;
import com.safevision.back.domain.model.Incident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentQueryService — consulta de incidentes ya registrados")
class IncidentQueryServiceTest {

    @Mock private IncidentRepositoryPort incidentRepo;
    @Mock private EvidenceRepositoryPort evidenceRepo;
    @Mock private EvidenceStoragePort presignService;

    private IncidentQueryService service;

    @BeforeEach
    void setUp() {
        service = new IncidentQueryService(incidentRepo, evidenceRepo, presignService);
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
    @DisplayName("findByFilter sin from/to/page/size → rango por defecto (2000-01-01 .. ahora), page=1 size=20")
    void buscarPorFiltro_sinParametros_usaDefaults() {
        Incident sample = new Incident(100L, 10L, 20L, 1L, "ext-sample",
                new String[]{"helmet"}, LocalDateTime.now(), LocalDateTime.now());
        when(incidentRepo.findByFilterPaged(eq(1L), any(), any(), any(), eq(20), eq(0L)))
                .thenReturn(Flux.just(sample));
        when(incidentRepo.countByFilter(eq(1L), any(), any(), any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.findByFilter(1L, null, null, null, null, null))
                .assertNext(paged -> {
                    assertThat(paged.items()).hasSize(1);
                    assertThat(paged.page()).isEqualTo(1);
                    assertThat(paged.size()).isEqualTo(20);
                    assertThat(paged.totalItems()).isEqualTo(1);
                    assertThat(paged.totalPages()).isEqualTo(1);
                })
                .verifyComplete();

        verify(incidentRepo).findByFilterPaged(eq(1L), any(),
                eq(LocalDateTime.of(2000, 1, 1, 0, 0)), any(), eq(20), eq(0L));
    }

    @Test
    @DisplayName("findByFilter con size=200 lo clampea a 50 (máximo por consulta)")
    void buscarPorFiltro_sizeExcedeMaximo_seClampeaA50() {
        when(incidentRepo.findByFilterPaged(any(), any(), any(), any(), eq(50), anyLong())).thenReturn(Flux.empty());
        when(incidentRepo.countByFilter(any(), any(), any(), any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.findByFilter(null, null, null, null, 1, 200))
                .assertNext(paged -> assertThat(paged.size()).isEqualTo(50))
                .verifyComplete();

        verify(incidentRepo).findByFilterPaged(any(), any(), any(), any(), eq(50), anyLong());
    }

    @Test
    @DisplayName("findByFilter con page=3 size=20 → offset=40")
    void buscarPorFiltro_page3_calculaOffsetCorrecto() {
        when(incidentRepo.findByFilterPaged(any(), any(), any(), any(), eq(20), eq(40L))).thenReturn(Flux.empty());
        when(incidentRepo.countByFilter(any(), any(), any(), any())).thenReturn(Mono.just(45L));

        StepVerifier.create(service.findByFilter(null, null, null, null, 3, 20))
                .assertNext(paged -> {
                    assertThat(paged.page()).isEqualTo(3);
                    assertThat(paged.totalItems()).isEqualTo(45);
                    assertThat(paged.totalPages()).isEqualTo(3);
                })
                .verifyComplete();

        verify(incidentRepo).findByFilterPaged(any(), any(), any(), any(), eq(20), eq(40L));
    }

    @Test
    @DisplayName("findEvidenceByIncidentId: incidente existente -> presigna solo filas con storageKey")
    void findEvidenceByIncidentId_presignaSoloFilasConStorageKey() {
        Incident existing = new Incident(100L, 10L, 20L, 1L, "cv-uuid-1",
                new String[]{"helmet"}, LocalDateTime.now(), LocalDateTime.now());
        when(incidentRepo.findById(100L)).thenReturn(Mono.just(existing));
        Evidence photo = new Evidence(1L, 100L, "PHOTO", null,
                "incidents/2026-08-10/cv-uuid-1/photo.jpg", null, null, LocalDateTime.now());
        Evidence legacyPhoto = new Evidence(2L, 100L, "PHOTO", "ZmFrZQ==", null, null, null, LocalDateTime.now());
        when(evidenceRepo.findByIncidentId(100L)).thenReturn(Flux.just(photo, legacyPhoto));
        when(presignService.presignGetUrl("incidents/2026-08-10/cv-uuid-1/photo.jpg"))
                .thenReturn("https://s3.amazonaws.com/bucket/incidents/2026-08-10/cv-uuid-1/photo.jpg?sig=x");

        StepVerifier.create(service.findEvidenceByIncidentId(100L))
                .assertNext(response -> assertThat(response.url())
                        .isEqualTo("https://s3.amazonaws.com/bucket/incidents/2026-08-10/cv-uuid-1/photo.jpg?sig=x"))
                .assertNext(response -> assertThat(response.url()).isNull())
                .verifyComplete();

        verify(presignService, times(1)).presignGetUrl(anyString());
    }

    @Test
    @DisplayName("findEvidenceByIncidentId: incidente inexistente -> 404, no consulta evidencia")
    void findEvidenceByIncidentId_incidenteInexistente_404() {
        when(incidentRepo.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findEvidenceByIncidentId(999L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(evidenceRepo, never()).findByIncidentId(any());
    }
}
