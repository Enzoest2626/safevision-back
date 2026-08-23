package com.safevision.back.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JwtService — emisión y validación de tokens")
class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-jwt-secret-safevision-minimum-32-bytes-long", 480);

    @Test
    @DisplayName("generateToken produce un JWT válido con el subject y expiración correctos")
    void generateToken_producesValidToken() {
        String token = jwtService.generateToken("jperez", "SUPERVISOR");

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractUsername(token)).isEqualTo("jperez");
    }

    @Test
    @DisplayName("isValid retorna false para un token malformado")
    void isValid_tokenMalformado_retornaFalse() {
        assertThat(jwtService.isValid("no-es-un-jwt")).isFalse();
    }

    @Test
    @DisplayName("isValid retorna false para un token firmado con otro secreto")
    void isValid_secretoDistinto_retornaFalse() {
        JwtService otherService = new JwtService("otro-secreto-completamente-distinto-de-32-bytes", 480);
        String token = otherService.generateToken("jperez", "SUPERVISOR");

        assertThat(jwtService.isValid(token)).isFalse();
    }

    @Test
    @DisplayName("expirationSeconds refleja los minutos configurados")
    void expirationSeconds_reflejaConfiguracion() {
        assertThat(jwtService.expirationSeconds()).isEqualTo(480L * 60);
    }
}
