package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.UserService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.UserRequest;
import com.safevision.back.infrastructure.web.dto.UserResponse;
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
@DisplayName("UserController — HTTP")
class UserControllerTest {

    @Mock
    private UserService service;

    private WebTestClient client;

    private UserResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new UserController(service))
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put("username", "supervisor1");
                    return chain.filter(exchange);
                })
                .build();
        sampleResponse = new UserResponse(1L, "jperez", "jperez@safevision.com", 1L, "SUPERVISOR", "999999999",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/users retorna 200 con lista envuelta en ApiEnvelope")
    void findAll_retorna200() {
        when(service.findAll()).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/users")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<UserResponse>>>() {})
                .value(envelope -> assertThat(envelope.data()).hasSize(1));
    }

    @Test
    @DisplayName("GET /api/v1/users/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(1L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/users/{id}", 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<UserResponse>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/users/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(99L)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        client.get().uri("/users/{id}", 99L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /api/v1/users retorna 201")
    void create_retorna201() {
        when(service.create(any(UserRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserRequest("jperez", "jperez@safevision.com", "password123", "SUPERVISOR", "999999999"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("POST /api/v1/users con email inválido retorna 400 (validación @Email)")
    void create_emailInvalido_retorna400() {
        client.post().uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserRequest("jperez", "no-es-un-email", "password123", "SUPERVISOR", "999999999"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("PUT /api/v1/users/{id} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), any(UserRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.put().uri("/users/{id}", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserRequest("jperez", "jperez@safevision.com", "password123", "SUPERVISOR", "999999999"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("DELETE /api/v1/users/{id} retorna 200 (no 204 — ahora lleva ApiEnvelope en el body)")
    void delete_retorna200() {
        when(service.delete(anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/users/{id}", 1L)
                .exchange()
                .expectStatus().isOk();
    }
}
