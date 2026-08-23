package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.WorkerService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.WorkerRequest;
import com.safevision.back.infrastructure.web.dto.WorkerResponse;
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
@DisplayName("WorkerController — HTTP")
class WorkerControllerTest {

    @Mock
    private WorkerService service;

    private WebTestClient client;

    private WorkerResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new WorkerController(service)).build();
        sampleResponse = new WorkerResponse(1L, 10L, 3, "Juan", "Perez", "Albañil",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/workers retorna 200 con lista envuelta en ApiEnvelope")
    void findAll_retorna200() {
        when(service.findAll()).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/workers")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<WorkerResponse>>>() {})
                .value(envelope -> assertThat(envelope.data()).hasSize(1));
    }

    @Test
    @DisplayName("GET /api/v1/workers/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(1L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/workers/{id}", 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<WorkerResponse>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/workers/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(99L)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found")));

        client.get().uri("/api/v1/workers/{id}", 99L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /api/v1/workers retorna 201")
    void create_retorna201() {
        when(service.create(any(WorkerRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/api/v1/workers")
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkerRequest(10L, 3, "Juan", "Perez", "Albañil"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("PUT /api/v1/workers/{id} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), any(WorkerRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.put().uri("/api/v1/workers/{id}", 1L)
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WorkerRequest(10L, 3, "Juan", "Perez", "Albañil"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("DELETE /api/v1/workers/{id} retorna 200 (no 204 — ahora lleva ApiEnvelope en el body)")
    void delete_retorna200() {
        when(service.delete(anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/api/v1/workers/{id}", 1L)
                .header("X-Username", "supervisor1")
                .exchange()
                .expectStatus().isOk();
    }
}
