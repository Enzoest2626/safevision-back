package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.CameraService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.web.dto.CameraResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("CameraController — HTTP")
class CameraControllerTest {

    @Mock
    private CameraService service;

    private WebTestClient client;

    private CameraResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new CameraController(service)).build();
        sampleResponse = new CameraResponse(1L, 10L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/cameras retorna 200 con lista envuelta en ApiEnvelope")
    void findAll_retorna200() {
        when(service.findAll()).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/cameras")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<CameraResponse>>>() {})
                .value(envelope -> assertThat(envelope.data()).hasSize(1));
    }

    @Test
    @DisplayName("GET /api/v1/cameras/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(1L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/cameras/{id}", 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<CameraResponse>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/cameras/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(99L)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Camera not found")));

        client.get().uri("/api/v1/cameras/{id}", 99L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /api/v1/cameras retorna 201")
    void create_retorna201() {
        when(service.create(any(CameraRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/api/v1/cameras")
                .header("X-Username", "supervisor1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new CameraRequest(10L, null, "CAM-01", "Entrada", "10.0.0.5", null))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("PUT /api/v1/cameras/{id} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), any(CameraRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.put().uri("/api/v1/cameras/{id}", 1L)
                .header("X-Username", "supervisor1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new CameraRequest(10L, null, "CAM-01", "Entrada", "10.0.0.5", null))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("DELETE /api/v1/cameras/{id} retorna 200 (no 204 — ahora lleva ApiEnvelope en el body)")
    void delete_retorna200() {
        when(service.delete(anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/api/v1/cameras/{id}", 1L)
                .header("X-Username", "supervisor1")
                .exchange()
                .expectStatus().isOk();
    }
}
