package com.safevision.back.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.dto.SiteContactRequest;
import com.safevision.back.dto.SiteContactResponse;
import com.safevision.back.service.SiteContactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
@DisplayName("SiteContactController — HTTP")
class SiteContactControllerTest {

    @Mock
    private SiteContactService service;

    private WebTestClient client;

    private static final Long SITE_ID = 10L;

    private SiteContactResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new SiteContactController(service)).build();
        sampleResponse = new SiteContactResponse(1L, SITE_ID, "Supervisor", "999999999", "111222333",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/sites/{siteId}/contacts retorna 200 con lista")
    void findBySite_retorna200() {
        when(service.findBySite(SITE_ID)).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/sites/{siteId}/contacts", SITE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(SiteContactResponse.class).hasSize(1);
    }

    @Test
    @DisplayName("POST /api/v1/sites/{siteId}/contacts retorna 201")
    void create_retorna201() {
        when(service.create(anyLong(), any(SiteContactRequest.class), anyString()))
                .thenReturn(Mono.just(sampleResponse));

        client.post().uri("/api/v1/sites/{siteId}/contacts", SITE_ID)
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest("Supervisor", "999999999", "111222333"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("POST /api/v1/sites/{siteId}/contacts con teléfono inválido retorna 400")
    void create_telefonoInvalido_retorna400() {
        client.post().uri("/api/v1/sites/{siteId}/contacts", SITE_ID)
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest("Supervisor", "abc", "111222333"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("PUT /api/v1/sites/{siteId}/contacts/{contactId} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), anyLong(), any(SiteContactRequest.class), anyString()))
                .thenReturn(Mono.just(sampleResponse));

        client.put().uri("/api/v1/sites/{siteId}/contacts/{contactId}", SITE_ID, 1L)
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest("Supervisor", "999999999", "111222333"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("PUT /api/v1/sites/{siteId}/contacts/{contactId} inexistente retorna 404")
    void update_inexistente_retorna404() {
        when(service.update(anyLong(), anyLong(), any(SiteContactRequest.class), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found")));

        client.put().uri("/api/v1/sites/{siteId}/contacts/{contactId}", SITE_ID, 99L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest("Supervisor", "999999999", "111222333"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("DELETE /api/v1/sites/{siteId}/contacts/{contactId} retorna 204")
    void delete_retorna204() {
        when(service.delete(anyLong(), anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/api/v1/sites/{siteId}/contacts/{contactId}", SITE_ID, 1L)
                .header("X-Username", "supervisor1")
                .exchange()
                .expectStatus().isNoContent();
    }
}
