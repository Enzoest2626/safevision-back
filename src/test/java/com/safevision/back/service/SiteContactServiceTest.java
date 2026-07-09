package com.safevision.back.service;

import com.safevision.back.dto.SiteContactRequest;
import com.safevision.back.model.SiteContact;
import com.safevision.back.repository.SiteContactRepository;
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
@DisplayName("SiteContactService — CRUD de contactos por obra")
class SiteContactServiceTest {

    @Mock
    private SiteContactRepository repository;

    private SiteContactService service;

    private static final Long SITE_ID = 10L;

    private final SiteContact contact = new SiteContact(1L, SITE_ID, "Supervisor",
            "999999999", "111222333", true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final SiteContactRequest request = new SiteContactRequest("Supervisor", "999999999", "111222333");

    @BeforeEach
    void setUp() {
        service = new SiteContactService(repository);
    }

    @Test
    @DisplayName("findBySite retorna solo contactos activos de la obra")
    void findBySite_retornaActivos() {
        when(repository.findBySiteIdAndActiveTrue(SITE_ID)).thenReturn(Flux.just(contact));

        StepVerifier.create(service.findBySite(SITE_ID))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Supervisor"))
                .verifyComplete();
    }

    @Test
    @DisplayName("create persiste el contacto nuevo bajo la obra")
    void create_persisteContacto() {
        when(repository.save(any(SiteContact.class))).thenReturn(Mono.just(contact));

        StepVerifier.create(service.create(SITE_ID, request, "supervisor1"))
                .assertNext(response -> assertThat(response.siteId()).isEqualTo(SITE_ID))
                .verifyComplete();
    }

    @Test
    @DisplayName("update sobre contacto existente de la obra persiste cambios")
    void update_existente_persisteCambios() {
        when(repository.findById(1L)).thenReturn(Mono.just(contact));
        when(repository.save(any(SiteContact.class))).thenReturn(Mono.just(contact));

        StepVerifier.create(service.update(SITE_ID, 1L, request, "supervisor1"))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Supervisor"))
                .verifyComplete();
    }

    @Test
    @DisplayName("update con contacto de otra obra lanza 404")
    void update_contactoDeOtraObra_lanzaNotFound() {
        when(repository.findById(1L)).thenReturn(Mono.just(contact));

        StepVerifier.create(service.update(999L, 1L, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("delete desactiva el contacto (soft delete)")
    void delete_existente_desactiva() {
        when(repository.findById(1L)).thenReturn(Mono.just(contact));
        when(repository.save(any(SiteContact.class))).thenReturn(Mono.just(contact));

        StepVerifier.create(service.delete(SITE_ID, 1L, "supervisor1"))
                .verifyComplete();

        verify(repository).save(argThat(c -> !c.active()));
    }

    @Test
    @DisplayName("delete sobre contacto inexistente lanza 404")
    void delete_inexistente_lanzaNotFound() {
        when(repository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(SITE_ID, 99L, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(repository, never()).save(any());
    }
}
