package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.safevision.back.application.ports.out.ZoneRepositoryPort;
import com.safevision.back.domain.model.Zone;
import com.safevision.back.infrastructure.web.dto.ZoneRequest;
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
@DisplayName("ZoneService — CRUD de zonas por obra")
class ZoneServiceTest {

    @Mock
    private ZoneRepositoryPort repository;

    private ZoneService service;

    private static final Long SITE_ID = 10L;

    private final Zone zone = new Zone(1L, SITE_ID, "ZONA-A", "Piso 2", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final ZoneRequest request = new ZoneRequest("ZONA-A", "Piso 2");

    @BeforeEach
    void setUp() {
        service = new ZoneService(repository);
        lenient().when(repository.existsBySiteIdAndCode(anyLong(), anyString())).thenReturn(Mono.just(false));
    }

    @Test
    @DisplayName("findBySite retorna solo zonas activas de la obra")
    void findBySite_retornaActivas() {
        when(repository.findBySiteIdAndActiveTrue(SITE_ID)).thenReturn(Flux.just(zone));

        StepVerifier.create(service.findBySite(SITE_ID))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Piso 2"))
                .verifyComplete();
    }

    @Test
    @DisplayName("create persiste la zona nueva bajo la obra")
    void create_persisteZona() {
        when(repository.save(any(Zone.class))).thenReturn(Mono.just(zone));

        StepVerifier.create(service.create(SITE_ID, request, "supervisor1"))
                .assertNext(response -> assertThat(response.siteId()).isEqualTo(SITE_ID))
                .verifyComplete();
    }

    @Test
    @DisplayName("create con código duplicado en la misma obra lanza 409")
    void create_codigoDuplicado_lanzaConflict() {
        when(repository.existsBySiteIdAndCode(SITE_ID, "ZONA-A")).thenReturn(Mono.just(true));

        StepVerifier.create(service.create(SITE_ID, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.CONFLICT)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create sin código lo autogenera")
    void create_sinCodigo_autogenera() {
        ZoneRequest sinCodigo = new ZoneRequest(null, "Piso 2");
        when(repository.save(any(Zone.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.create(SITE_ID, sinCodigo, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).startsWith("ZONE-"))
                .verifyComplete();

        verify(repository, never()).existsBySiteIdAndCode(any(), any());
    }

    @Test
    @DisplayName("update sobre zona existente de la obra persiste cambios")
    void update_existente_persisteCambios() {
        when(repository.findById(1L)).thenReturn(Mono.just(zone));
        when(repository.save(any(Zone.class))).thenReturn(Mono.just(zone));

        StepVerifier.create(service.update(SITE_ID, 1L, request, "supervisor1"))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Piso 2"))
                .verifyComplete();
    }

    @Test
    @DisplayName("update ignora el código del request — es inmutable tras la creación")
    void update_conservaCodigoOriginal() {
        ZoneRequest conOtroCodigo = new ZoneRequest("ZONA-OTRA", "Piso 2");
        when(repository.findById(1L)).thenReturn(Mono.just(zone));
        when(repository.save(any(Zone.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.update(SITE_ID, 1L, conOtroCodigo, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).isEqualTo("ZONA-A"))
                .verifyComplete();

        verify(repository).save(argThat(z -> z.code().equals("ZONA-A")));
    }

    @Test
    @DisplayName("update con zona de otra obra lanza 404")
    void update_zonaDeOtraObra_lanzaNotFound() {
        when(repository.findById(1L)).thenReturn(Mono.just(zone));

        StepVerifier.create(service.update(999L, 1L, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("update sobre zona inexistente lanza 404")
    void update_inexistente_lanzaNotFound() {
        when(repository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(SITE_ID, 99L, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("delete desactiva la zona (soft delete)")
    void delete_existente_desactiva() {
        when(repository.findById(1L)).thenReturn(Mono.just(zone));
        when(repository.save(any(Zone.class))).thenReturn(Mono.just(zone));

        StepVerifier.create(service.delete(SITE_ID, 1L, "supervisor1"))
                .verifyComplete();

        verify(repository).save(argThat(z -> !z.active()));
    }

    @Test
    @DisplayName("delete sobre zona inexistente lanza 404")
    void delete_inexistente_lanzaNotFound() {
        when(repository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(SITE_ID, 99L, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(repository, never()).save(any());
    }
}
