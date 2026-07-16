package com.safevision.back.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.dto.SiteRequest;
import com.safevision.back.dto.SiteResponse;
import com.safevision.back.service.SiteService;
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
@DisplayName("SiteController — HTTP")
class SiteControllerTest {

    @Mock
    private SiteService service;

    private WebTestClient client;

    private SiteResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new SiteController(service)).build();
        sampleResponse = new SiteResponse(1L, "Main-Site", "Lima", true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/sites retorna 200 con lista")
    void findAll_retorna200() {
        when(service.findAll()).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/sites")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(SiteResponse.class).hasSize(1);
    }

    @Test
    @DisplayName("GET /api/v1/sites/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(1L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/sites/{id}", 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(SiteResponse.class);
    }

    @Test
    @DisplayName("GET /api/v1/sites/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(99L)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")));

        client.get().uri("/api/v1/sites/{id}", 99L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /api/v1/sites retorna 201")
    void create_retorna201() {
        when(service.create(any(SiteRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/api/v1/sites")
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteRequest("Main-Site", "Lima"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("PUT /api/v1/sites/{id} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), any(SiteRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.put().uri("/api/v1/sites/{id}", 1L)
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SiteRequest("Main-Site", "Lima"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("DELETE /api/v1/sites/{id} retorna 204")
    void delete_retorna204() {
        when(service.delete(anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/api/v1/sites/{id}", 1L)
                .header("X-Username", "supervisor1")
                .exchange()
                .expectStatus().isNoContent();
    }
}
