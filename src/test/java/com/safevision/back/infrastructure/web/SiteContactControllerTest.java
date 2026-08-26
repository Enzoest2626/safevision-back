package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.SiteContactService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

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
        client = WebTestClient.bindToController(new SiteContactController(service))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put("username", "supervisor1");
                    return chain.filter(exchange);
                })
                .build();
        sampleResponse = new SiteContactResponse(1L, SITE_ID, "Supervisor", "999999999", "111222333",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/sites/{siteId}/contacts retorna 200 con lista envuelta en ApiEnvelope")
    void findBySite_retorna200() {
        when(service.findBySite(SITE_ID)).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/sites/{siteId}/contacts", SITE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<SiteContactResponse>>>() {})
                .value(envelope -> assertThat(envelope.data()).hasSize(1));
    }

    @Test
    @DisplayName("POST /api/v1/sites/{siteId}/contacts retorna 201")
    void create_retorna201() {
        when(service.create(anyLong(), any(SiteContactRequest.class), anyString()))
                .thenReturn(Mono.just(sampleResponse));

        client.post().uri("/sites/{siteId}/contacts", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest("Supervisor", "999999999", "111222333"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("POST /api/v1/sites/{siteId}/contacts con teléfono inválido retorna 400")
    void create_telefonoInvalido_retorna400() {
        client.post().uri("/sites/{siteId}/contacts", SITE_ID)
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

        client.put().uri("/sites/{siteId}/contacts/{contactId}", SITE_ID, 1L)
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

        client.put().uri("/sites/{siteId}/contacts/{contactId}", SITE_ID, 99L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteContactRequest("Supervisor", "999999999", "111222333"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("DELETE /api/v1/sites/{siteId}/contacts/{contactId} retorna 200 (no 204 — ahora lleva ApiEnvelope en el body)")
    void delete_retorna200() {
        when(service.delete(anyLong(), anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/sites/{siteId}/contacts/{contactId}", SITE_ID, 1L)
                .exchange()
                .expectStatus().isOk();
    }
}
