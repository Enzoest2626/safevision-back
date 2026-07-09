package com.safevision.back.service;

import com.safevision.back.dto.SiteRequest;
import com.safevision.back.model.Site;
import com.safevision.back.repository.SiteRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SiteService — CRUD de obras")
class SiteServiceTest {

    @Mock
    private SiteRepository siteRepository;

    private SiteService service;

    private final Site site = new Site(1L, "Main-Site", "Lima", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final SiteRequest request = new SiteRequest("Main-Site", "Lima");

    @BeforeEach
    void setUp() {
        service = new SiteService(siteRepository);
    }

    @Test
    @DisplayName("findAll retorna solo obras activas")
    void findAll_retornaActivas() {
        when(siteRepository.findByActiveTrue()).thenReturn(Flux.just(site));

        StepVerifier.create(service.findAll())
                .assertNext(response -> assertThat(response.name()).isEqualTo("Main-Site"))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById existente retorna la obra")
    void findById_existente_retornaObra() {
        when(siteRepository.findById(1L)).thenReturn(Mono.just(site));

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById inexistente lanza 404")
    void findById_inexistente_lanzaNotFound() {
        when(siteRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(99L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("create persiste la obra nueva")
    void create_persisteObra() {
        when(siteRepository.save(any(Site.class))).thenReturn(Mono.just(site));

        StepVerifier.create(service.create(request, "supervisor1"))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Main-Site"))
                .verifyComplete();

        verify(siteRepository).save(any(Site.class));
    }

    @Test
    @DisplayName("update sobre obra existente persiste cambios")
    void update_existente_persisteCambios() {
        when(siteRepository.findById(1L)).thenReturn(Mono.just(site));
        when(siteRepository.save(any(Site.class))).thenReturn(Mono.just(site));

        StepVerifier.create(service.update(1L, request, "supervisor1"))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Main-Site"))
                .verifyComplete();
    }

    @Test
    @DisplayName("update sobre obra inexistente lanza 404")
    void update_inexistente_lanzaNotFound() {
        when(siteRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(siteRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete desactiva la obra (soft delete)")
    void delete_existente_desactiva() {
        when(siteRepository.findById(1L)).thenReturn(Mono.just(site));
        when(siteRepository.save(any(Site.class))).thenReturn(Mono.just(site));

        StepVerifier.create(service.delete(1L, "supervisor1"))
                .verifyComplete();

        verify(siteRepository).save(argThat(s -> !s.active()));
    }

    @Test
    @DisplayName("delete sobre obra inexistente lanza 404")
    void delete_inexistente_lanzaNotFound() {
        when(siteRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(99L, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(siteRepository, never()).save(any());
    }
}
