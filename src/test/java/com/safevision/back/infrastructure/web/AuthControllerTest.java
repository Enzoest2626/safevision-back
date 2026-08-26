package com.safevision.back.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.AuthService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.LoginRequest;
import com.safevision.back.infrastructure.web.dto.LoginResponse;
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
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — HTTP")
class AuthControllerTest {

    @Mock
    private AuthService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new AuthController(service)).build();
    }

    @Test
    @DisplayName("POST /api/v1/auth/login con credenciales correctas retorna 200 y token")
    void login_credencialesCorrectas_retorna200() {
        LoginResponse response = new LoginResponse("jwt-token-value", "Bearer", 28800L, "jperez", "SUPERVISOR");
        when(service.login(any(LoginRequest.class))).thenReturn(Mono.just(response));

        client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("jperez", "correct-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<LoginResponse>>() {});
    }

    @Test
    @DisplayName("POST /api/v1/auth/login con credenciales inválidas retorna 401")
    void login_credencialesInvalidas_retorna401() {
        when(service.login(any(LoginRequest.class))).thenReturn(
                Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")));

        client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("jperez", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("POST /api/v1/auth/login sin username retorna 400 (validación @NotBlank)")
    void login_sinUsername_retorna400() {
        client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest("", "correct-password"))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
