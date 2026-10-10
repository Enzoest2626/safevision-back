package com.safevision.back.infrastructure.web;

import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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

    /**
     * Violación de un índice UNIQUE a nivel de base de datos (código o nombre
     * duplicado que ningún service pre-valida, o dos altas concurrentes que
     * esquivaron la validación) → 409: el registro ya existe. Mismo criterio
     * que el 409 que ya lanzan los services por código duplicado.
     *
     * <p>{@link DuplicateKeyException} es subclase de
     * {@link DataIntegrityViolationException}: Spring resuelve contra el
     * manejador más específico, así que este tipo de error nunca cae en el de
     * integridad general (400). El mensaje técnico de la excepción original no
     * se expone al cliente — solo la descripción fija de abajo.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Mono<ResponseEntity<ApiEnvelope<Void>>> handleDuplicateKey(DuplicateKeyException ex) {
        log.error("Clave única duplicada en base de datos", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiEnvelope.error(HttpStatus.CONFLICT, "CONFLICT",
                        "Ya existe un registro con el mismo código o nombre")));
    }

    /**
     * Cualquier otra violación de integridad a nivel de base de datos (clave
     * foránea a un siteId/zoneId inexistente, restricción CHECK, valor fuera
     * de rango o demasiado largo) → 400: el problema está en los datos
     * enviados, no en el servidor. Hoy solo llegan acá los casos que no son de
     * clave duplicada (ver {@link #handleDuplicateKey}); sin este manejador
     * terminarían en el genérico como 500 INTERNAL_ERROR. El mensaje técnico
     * de la excepción original no se expone al cliente.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Mono<ResponseEntity<ApiEnvelope<Void>>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.error("Violación de integridad de datos", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiEnvelope.error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                        "Los datos enviados no son válidos: referencia inexistente o valor fuera de rango")));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiEnvelope<Void>>> handleGeneric(Exception ex) {
        log.error("Error no controlado", ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage())));
    }
}
