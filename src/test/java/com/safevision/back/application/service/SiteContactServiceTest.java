package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.safevision.back.application.ports.out.SiteContactRepositoryPort;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
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
@DisplayName("SiteContactService — CRUD de contactos por obra")
class SiteContactServiceTest {

    @Mock
    private SiteContactRepositoryPort repository;

    private SiteContactService service;

    private static final Long SITE_ID = 10L;

    private final SiteContact contact = new SiteContact(1L, SITE_ID, "Supervisor",
            "999999999", "111222333", null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final SiteContactRequest request = new SiteContactRequest("Supervisor", "999999999", "111222333");

    @BeforeEach
    void setUp() {
        service = new SiteContactService(repository);
    }

    @Test
    @DisplayName("findBySite retorna todos los contactos de la obra, activos e inactivos")
    void findBySite_retornaActivosEInactivos() {
        SiteContact inactivo = new SiteContact(2L, SITE_ID, "De vacaciones", "999999999", "444555666",
                null, false, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(repository.findBySiteId(SITE_ID)).thenReturn(Flux.just(contact, inactivo));

        StepVerifier.create(service.findBySite(SITE_ID))
                .expectNextMatches(response -> response.name().equals("Supervisor") && response.active())
                .expectNextMatches(response -> response.name().equals("De vacaciones") && !response.active())
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

    @Test
    @DisplayName("create sin telegramChatId genera un codigo de vinculacion de 6 digitos")
    void create_sinTelegramChatId_generaLinkCode() {
        SiteContactRequest sinChatId = new SiteContactRequest("Supervisor", "999999999", null);
        when(repository.save(any(SiteContact.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.create(SITE_ID, sinChatId, "supervisor1"))
                .assertNext(response -> {
                    assertThat(response.telegramChatId()).isNull();
                    assertThat(response.telegramLinkCode()).matches("\\d{6}");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("create con telegramChatId no genera codigo de vinculacion")
    void create_conTelegramChatId_noGeneraLinkCode() {
        when(repository.save(any(SiteContact.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.create(SITE_ID, request, "supervisor1"))
                .assertNext(response -> assertThat(response.telegramLinkCode()).isNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("update sin telegramChatId conserva el codigo de vinculacion pendiente")
    void update_sinTelegramChatId_conservaLinkCodeExistente() {
        SiteContact pendiente = new SiteContact(1L, SITE_ID, "Supervisor", "999999999", null,
                "654321", true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        SiteContactRequest sinChatId = new SiteContactRequest("Supervisor Editado", "999999999", null);
        when(repository.findById(1L)).thenReturn(Mono.just(pendiente));
        when(repository.save(any(SiteContact.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.update(SITE_ID, 1L, sinChatId, "supervisor1"))
                .assertNext(response -> assertThat(response.telegramLinkCode()).isEqualTo("654321"))
                .verifyComplete();
    }

    @Test
    @DisplayName("setActive(false) pausa un contacto activo sin tocar su vinculo de Telegram")
    void setActive_false_desactivaSinTocarVinculo() {
        SiteContact vinculado = new SiteContact(1L, SITE_ID, "Supervisor", "999999999", "111222333",
                null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(repository.findById(1L)).thenReturn(Mono.just(vinculado));
        when(repository.save(any(SiteContact.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.setActive(SITE_ID, 1L, false, "supervisor1"))
                .assertNext(response -> {
                    assertThat(response.active()).isFalse();
                    assertThat(response.telegramChatId()).isEqualTo("111222333");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("setActive(true) reactiva un contacto previamente pausado")
    void setActive_true_reactivaUnoPausado() {
        SiteContact pausado = new SiteContact(1L, SITE_ID, "Supervisor", "999999999", "111222333",
                null, false, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(repository.findById(1L)).thenReturn(Mono.just(pausado));
        when(repository.save(any(SiteContact.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.setActive(SITE_ID, 1L, true, "supervisor1"))
                .assertNext(response -> assertThat(response.active()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("setActive sobre contacto de otra obra lanza 404")
    void setActive_contactoDeOtraObra_lanzaNotFound() {
        when(repository.findById(1L)).thenReturn(Mono.just(contact));

        StepVerifier.create(service.setActive(999L, 1L, false, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("tryLinkByCode con codigo valido setea el chat_id y limpia el codigo")
    void tryLinkByCode_codigoValido_vincula() {
        SiteContact pendiente = new SiteContact(1L, SITE_ID, "Supervisor", "999999999", null,
                "123456", true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(repository.findByTelegramLinkCodeAndActiveTrue("123456")).thenReturn(Mono.just(pendiente));
        when(repository.save(any(SiteContact.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.tryLinkByCode("123456", "chat-999"))
                .expectNext("Supervisor")
                .verifyComplete();

        verify(repository).save(argThat(c ->
                "chat-999".equals(c.telegramChatId()) && c.telegramLinkCode() == null));
    }

    @Test
    @DisplayName("tryLinkByCode con codigo desconocido no vincula nada")
    void tryLinkByCode_codigoDesconocido_noVincula() {
        when(repository.findByTelegramLinkCodeAndActiveTrue("000000")).thenReturn(Mono.empty());

        StepVerifier.create(service.tryLinkByCode("000000", "chat-999"))
                .verifyComplete();

        verify(repository, never()).save(any());
    }
}
