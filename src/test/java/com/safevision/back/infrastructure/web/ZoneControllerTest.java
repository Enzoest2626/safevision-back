package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.ZoneService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.ZoneRequest;
import com.safevision.back.infrastructure.web.dto.ZoneResponse;
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
@DisplayName("ZoneController — HTTP")
class ZoneControllerTest {

    @Mock
    private ZoneService service;

    private WebTestClient client;

    private static final Long SITE_ID = 10L;

    private ZoneResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new ZoneController(service))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put("username", "supervisor1");
                    return chain.filter(exchange);
                })
                .build();
        sampleResponse = new ZoneResponse(1L, SITE_ID, "ZONA-1", "Piso 2", true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/sites/{siteId}/zones retorna 200 con lista envuelta en ApiEnvelope")
    void findBySite_retorna200() {
        when(service.findBySite(SITE_ID)).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/sites/{siteId}/zones", SITE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<ZoneResponse>>>() {})
                .value(envelope -> assertThat(envelope.data()).hasSize(1));
    }

    @Test
    @DisplayName("POST /api/v1/sites/{siteId}/zones retorna 201")
    void create_retorna201() {
        when(service.create(anyLong(), any(ZoneRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/sites/{siteId}/zones", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ZoneRequest("ZONA-1", "Piso 2"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("PUT /api/v1/sites/{siteId}/zones/{zoneId} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), anyLong(), any(ZoneRequest.class), anyString()))
                .thenReturn(Mono.just(sampleResponse));

        client.put().uri("/sites/{siteId}/zones/{zoneId}", SITE_ID, 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ZoneRequest("ZONA-1", "Piso 2"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("PUT /api/v1/sites/{siteId}/zones/{zoneId} inexistente retorna 404")
    void update_inexistente_retorna404() {
        when(service.update(anyLong(), anyLong(), any(ZoneRequest.class), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone not found")));

        client.put().uri("/sites/{siteId}/zones/{zoneId}", SITE_ID, 99L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ZoneRequest("ZONA-1", "Piso 2"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("DELETE /api/v1/sites/{siteId}/zones/{zoneId} retorna 200 (no 204 — ahora lleva ApiEnvelope en el body)")
    void delete_retorna200() {
        when(service.delete(anyLong(), anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/sites/{siteId}/zones/{zoneId}", SITE_ID, 1L)
                .exchange()
                .expectStatus().isOk();
    }
}
