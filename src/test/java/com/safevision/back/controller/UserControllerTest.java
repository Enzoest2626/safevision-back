package com.safevision.back.controller;

import com.safevision.back.dto.UserRequest;
import com.safevision.back.dto.UserResponse;
import com.safevision.back.service.UserService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController — HTTP")
class UserControllerTest {

    @Mock
    private UserService service;

    private WebTestClient client;

    private UserResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new UserController(service)).build();
        sampleResponse = new UserResponse(1L, "jperez", "jperez@safevision.com", 1L, "999999999",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("GET /api/v1/users retorna 200 con lista")
    void findAll_retorna200() {
        when(service.findAll()).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/users")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserResponse.class).hasSize(1);
    }

    @Test
    @DisplayName("GET /api/v1/users/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(1L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/users/{id}", 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserResponse.class);
    }

    @Test
    @DisplayName("GET /api/v1/users/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(99L)).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        client.get().uri("/api/v1/users/{id}", 99L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /api/v1/users retorna 201")
    void create_retorna201() {
        when(service.create(any(UserRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.post().uri("/api/v1/users")
                .header("X-Username", "admin1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserRequest("jperez", "jperez@safevision.com", "password123", "SUPERVISOR", "999999999"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("POST /api/v1/users con email inválido retorna 400 (validación @Email)")
    void create_emailInvalido_retorna400() {
        client.post().uri("/api/v1/users")
                .header("X-Username", "admin1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserRequest("jperez", "no-es-un-email", "password123", "SUPERVISOR", "999999999"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("PUT /api/v1/users/{id} retorna 200")
    void update_retorna200() {
        when(service.update(anyLong(), any(UserRequest.class), anyString())).thenReturn(Mono.just(sampleResponse));

        client.put().uri("/api/v1/users/{id}", 1L)
                .header("X-Username", "admin1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserRequest("jperez", "jperez@safevision.com", "password123", "SUPERVISOR", "999999999"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("DELETE /api/v1/users/{id} retorna 204")
    void delete_retorna204() {
        when(service.delete(anyLong(), anyString())).thenReturn(Mono.empty());

        client.delete().uri("/api/v1/users/{id}", 1L)
                .header("X-Username", "admin1")
                .exchange()
                .expectStatus().isNoContent();
    }
}
