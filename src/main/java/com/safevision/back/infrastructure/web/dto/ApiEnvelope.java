package com.safevision.back.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Envoltorio estándar de toda respuesta HTTP de esta API. Éxito y error
 * comparten {@code status}/{@code datetime}/{@code error}; {@code data}
 * solo se llena en éxito, {@code errorCode}/{@code errorDescription} solo
 * en error ({@code @JsonInclude} oculta lo que no aplica en cada caso). El
 * HTTP status real de la respuesta (200/201/404/401/...) no cambia — este
 * campo {@code status} solo lo repite en el body para quien lea el JSON
 * sin mirar la cabecera HTTP.
 *
 * <p>No se llama {@code ApiResponse} para no chocar con la anotación
 * Swagger {@code io.swagger.v3.oas.annotations.responses.ApiResponse} que
 * ya se usa en todos los controllers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiEnvelope<T>(
        String status,
        String datetime,
        boolean error,
        T data,
        String errorCode,
        String errorDescription
) {

    public static <T> ApiEnvelope<T> ok(HttpStatus httpStatus, T data) {
        return new ApiEnvelope<>(String.valueOf(httpStatus.value()), Instant.now().toString(), false, data, null, null);
    }

    public static ApiEnvelope<Void> error(HttpStatus httpStatus, String errorCode, String errorDescription) {
        return new ApiEnvelope<>(String.valueOf(httpStatus.value()), Instant.now().toString(), true, null,
                errorCode, errorDescription);
    }

    /** Envuelve un {@code Mono<T>} — para findById/create/update. */
    public static <T> Mono<ApiEnvelope<T>> wrap(Mono<T> source, HttpStatus httpStatus) {
        return source.map(data -> ApiEnvelope.ok(httpStatus, data));
    }

    /**
     * Envuelve un {@code Flux<T>} como lista — para findAll. Junta el
     * stream antes de envolver: un {@code Flux} no puede emitir "el array
     * completo" elemento a elemento y a la vez llevar
     * status/datetime/error en el mismo objeto JSON, así que se pierde el
     * streaming incremental del listado a cambio del envoltorio uniforme.
     */
    public static <T> Mono<ApiEnvelope<List<T>>> wrapList(Flux<T> source, HttpStatus httpStatus) {
        return source.collectList().map(data -> ApiEnvelope.ok(httpStatus, data));
    }

    /**
     * Envuelve un {@code Mono<Void>} — para delete. El HTTP status pasa de
     * 204 a 200: 204 No Content no puede llevar body, y ahora todo
     * response lleva el envoltorio.
     */
    public static Mono<ApiEnvelope<Void>> wrapVoid(Mono<Void> source, HttpStatus httpStatus) {
        return source.then(Mono.just(ApiEnvelope.<Void>ok(httpStatus, null)));
    }
}
