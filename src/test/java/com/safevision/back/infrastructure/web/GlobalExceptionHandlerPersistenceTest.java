package com.safevision.back.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * TA34 (HU08) — una violación de integridad que escapa a la pre-validación de
 * los services (índice UNIQUE de un código/nombre duplicado, clave foránea a
 * un siteId/zoneId inexistente, CHECK o valor fuera de rango) debe salir como
 * 409/400 controlados y nunca como 500 INTERNAL_ERROR.
 *
 * <p>Los dos manejadores se invocan directamente con {@link StepVerifier} para
 * fijar status HTTP, {@code error}, {@code errorCode} y descripción; y además
 * se comprueba contra el resolver real de Spring (WebTestClient + controller
 * advice) que una {@link DuplicateKeyException} —subclase de
 * {@link DataIntegrityViolationException}— cae en su manejador específico
 * (409) y no en el general (400).
 */
@DisplayName("GlobalExceptionHandlerPersistenceTest — violaciones de integridad de la persistencia controladas (TA34)")
class GlobalExceptionHandlerPersistenceTest {

    /** Descripción exacta que recibe el cliente ante un registro duplicado. */
    private static final String DESCRIPCION_DUPLICADO = "Ya existe un registro con el mismo código o nombre";

    /** Descripción exacta que recibe el cliente ante cualquier otra violación. */
    private static final String DESCRIPCION_INTEGRIDAD =
            "Los datos enviados no son válidos: referencia inexistente o valor fuera de rango";

    private GlobalExceptionHandler handler;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        client = WebTestClient.bindToController(new ErroresDePersistenciaController())
                .controllerAdvice(handler)
                .build();
    }

    // ── Invocación directa de los dos manejadores nuevos ─────────────────

    @Test
    @DisplayName("DuplicateKeyException responde 409 CONFLICT con error=true, errorCode y descripción fijos")
    void duplicateKeyException_responde409Conflict() {
        DuplicateKeyException ex = new DuplicateKeyException(
                "duplicate key value violates unique constraint \"cameras_code_key\"");

        StepVerifier.create(handler.handleDuplicateKey(ex))
                .assertNext(response -> {
                    ApiEnvelope<Void> envelope = response.getBody();
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(envelope).isNotNull();
                    assertThat(envelope.status()).isEqualTo("409");
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("CONFLICT");
                    assertThat(envelope.errorDescription()).isEqualTo(DESCRIPCION_DUPLICADO);
                    // El mensaje técnico de la base de datos no se filtra al cliente
                    assertThat(envelope.errorDescription()).doesNotContain("cameras_code_key");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("DataIntegrityViolationException responde 400 BAD_REQUEST con error=true, errorCode y descripción fijos")
    void dataIntegrityViolation_responde400BadRequest() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "FK \"cameras_zone_id_fkey\" violada: zoneId inexistente");

        StepVerifier.create(handler.handleDataIntegrity(ex))
                .assertNext(response -> {
                    ApiEnvelope<Void> envelope = response.getBody();
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(envelope).isNotNull();
                    assertThat(envelope.status()).isEqualTo("400");
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("BAD_REQUEST");
                    assertThat(envelope.errorDescription()).isEqualTo(DESCRIPCION_INTEGRIDAD);
                    // El mensaje técnico de la base de datos no se filtra al cliente
                    assertThat(envelope.errorDescription()).doesNotContain("cameras_zone_id_fkey");
                })
                .verifyComplete();
    }

    // ── Spring elige el manejador más específico (no el genérico) ─────────

    @Test
    @DisplayName("Una DuplicateKeyException produce 409 y no 400 al pasar por el resolver real de Spring")
    void duplicateKeyException_produce409No400() {
        // DuplicateKeyException ES una DataIntegrityViolationException: si
        // Spring resolviera contra el manejador general respondería 400.
        assertThat(new DuplicateKeyException("x")).isInstanceOf(DataIntegrityViolationException.class);

        client.get().uri("/duplicado").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {})
                .value(envelope -> {
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("CONFLICT");
                    assertThat(envelope.errorCode()).isNotEqualTo("BAD_REQUEST");
                });
    }

    @Test
    @DisplayName("Una DataIntegrityViolationException (clave foránea) produce 400 al pasar por el resolver real de Spring")
    void dataIntegrityViolation_produce400() {
        client.get().uri("/integridad").exchange()
                .expectStatus().isBadRequest()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {})
                .value(envelope -> {
                    assertThat(envelope.error()).isTrue();
                    assertThat(envelope.errorCode()).isEqualTo("BAD_REQUEST");
                });
    }

    /**
     * Controlador mínimo que emite cada violación desde el retorno reactivo,
     * igual que haría un service al chocar contra la base de datos.
     */
    @RestController
    static class ErroresDePersistenciaController {

        @GetMapping("/duplicado")
        Mono<String> duplicado() {
            return Mono.error(new DuplicateKeyException(
                    "duplicate key value violates unique constraint \"zones_site_id_code_key\""));
        }

        @GetMapping("/integridad")
        Mono<String> integridad() {
            return Mono.error(new DataIntegrityViolationException(
                    "FK \"cameras_zone_id_fkey\" violada: zoneId inexistente"));
        }
    }
}
