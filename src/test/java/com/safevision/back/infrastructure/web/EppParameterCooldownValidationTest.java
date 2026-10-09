package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.safevision.back.application.service.EppParameterService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.EppParameterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CP43/CP44 — el cooldown por obra (HU07) debe ser un entero mayor a 0.
 *
 * <p>El rechazo tiene que ocurrir en la validación del request, antes de que
 * el servicio toque la base de datos: por eso se verifica que
 * {@code updateForSite} nunca se invoca. Se registra el
 * {@link GlobalExceptionHandler} para comprobar que el cuerpo de error usa el
 * sobre estándar de la API ({@code errorCode = "VALIDATION_ERROR"}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EppParameterCooldownValidationTest — cooldown no positivo rechazado en la capa web")
class EppParameterCooldownValidationTest {

    @Mock
    private EppParameterService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient
                .bindToController(new EppParameterController(service))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put("username", "supervisor1");
                    return chain.filter(exchange);
                })
                .build();
    }

    // ── CP43 — cooldownSeconds = 0 ────────────────────────────────────

    @Test
    @Tag("CP43")
    @DisplayName("CP43 — PUT con cooldownSeconds = 0 devuelve 400 VALIDATION_ERROR sin invocar al servicio")
    void cp43_cooldownCero_retorna400SinInvocarServicio() {
        String payload = "{\"requiredEpp\":[\"casco\"],\"cooldownSeconds\":0}";
        System.out.println("\n[CP43] PUT /api/v1/parameters/1 con cooldownSeconds = 0 (mínimo permitido: 1):");

        client.put().uri("/parameters/{siteId}", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {})
                .value(envelope -> {
                    System.out.println("       payload          = " + payload);
                    System.out.println("       HTTP status      = 400");
                    System.out.println("       error            = " + envelope.error());
                    System.out.println("       errorCode        = " + envelope.errorCode());
                    System.out.println("       errorDescription = " + envelope.errorDescription());
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("VALIDATION_ERROR");
                });

        verify(service, never()).updateForSite(anyLong(), any(EppParameterRequest.class), anyString());
        System.out.println("[CP43] 400 con VALIDATION_ERROR y servicio nunca invocado => PASA");
    }

    // ── CP44 — cooldownSeconds = -10 ──────────────────────────────────

    @Test
    @Tag("CP44")
    @DisplayName("CP44 — PUT con cooldownSeconds = -10 devuelve 400 VALIDATION_ERROR sin invocar al servicio")
    void cp44_cooldownNegativo_retorna400SinInvocarServicio() {
        String payload = "{\"requiredEpp\":[\"casco\"],\"cooldownSeconds\":-10}";
        System.out.println("\n[CP44] PUT /api/v1/parameters/1 con cooldownSeconds = -10 (negativo):");

        client.put().uri("/parameters/{siteId}", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {})
                .value(envelope -> {
                    System.out.println("       payload          = " + payload);
                    System.out.println("       HTTP status      = 400");
                    System.out.println("       error            = " + envelope.error());
                    System.out.println("       errorCode        = " + envelope.errorCode());
                    System.out.println("       errorDescription = " + envelope.errorDescription());
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("VALIDATION_ERROR");
                });

        verify(service, never()).updateForSite(anyLong(), any(EppParameterRequest.class), anyString());
        System.out.println("[CP44] 400 con VALIDATION_ERROR y servicio nunca invocado => PASA");
    }
}
