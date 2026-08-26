package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.SiteService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import com.safevision.back.infrastructure.web.dto.SiteResponse;
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
@DisplayName("SiteController — HTTP")
class SiteControllerTest {

    @Mock
    private SiteService service;

    private WebTestClient client;

    private SiteResponse sampleResponse;

    @BeforeEach
    void setUp() {
        // El filtro JWT real deja el username como atributo del exchange (ver
        // SecurityConfig) — bindToController no pasa por ese filtro, así que
        // se simula acá para que @RequestAttribute("username") resuelva.
        client = WebTestClient.bindToController(new SiteController(service))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put("username", "supervisor1");
                    return chain.filter(exchange);
                })
                .build();
        sampleResponse = new SiteResponse(1L, "OBRA-1", "Main-Site", "Lima", true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/sites retorna 200 con lista envuelta en ApiEnvelope")
    void findAll_retorna200() {
        when(service.findAll()).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/sites")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<SiteResponse>>>() {})
                .value(envelope -> {
                    assertThat(envelope.error()).isFalse();
                    assertThat(envelope.data()).hasSize(1);
                });
    }

    @Test
    @DisplayName("GET /api/v1/sites/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(1L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/sites/{id}", 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<SiteResponse>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/sites/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(99L)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")));

        client.get().uri("/sites/{id}", 99L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /api/v1/sites retorna 201")
    void create_retorna201() {
        when(service.create(any(SiteRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/sites")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteRequest("OBRA-1", "Main-Site", "Lima"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("PUT /api/v1/sites/{id} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), any(SiteRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.put().uri("/sites/{id}", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteRequest("OBRA-1", "Main-Site", "Lima"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("DELETE /api/v1/sites/{id} retorna 200 (no 204 — ahora lleva ApiEnvelope en el body)")
    void delete_retorna200() {
        when(service.delete(anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/sites/{id}", 1L)
                .exchange()
                .expectStatus().isOk();
    }
}
