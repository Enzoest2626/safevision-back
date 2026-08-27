package com.safevision.back.infrastructure.web;

import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Convierte toda excepción no capturada en el {@link ApiEnvelope} de error
 * — misma forma para los 400/401/404 que ya se lanzan como
 * {@link ResponseStatusException} en los services (no hace falta tocarlos)
 * y para lo demás. El HTTP status real de la respuesta no cambia.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiEnvelope<Void>>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return Mono.just(ResponseEntity.status(status).body(ApiEnvelope.error(status, status.name(), reason)));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiEnvelope<Void>>> handleValidation(WebExchangeBindException ex) {
        // getDefaultMessage() ya es el texto pensado para el usuario final —
        // cada DTO define su propio `message` en la anotación (ver
        // infrastructure/web/dto/*Request.java). Sin field/regexp crudo acá:
        // eso fue justamente el bug que se corrigió (ver CLAUDE.md).
        String description = ex.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Los datos enviados no son válidos");
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiEnvelope.error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", description)));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiEnvelope<Void>>> handleGeneric(Exception ex) {
        log.error("Error no controlado", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage())));
    }
}
