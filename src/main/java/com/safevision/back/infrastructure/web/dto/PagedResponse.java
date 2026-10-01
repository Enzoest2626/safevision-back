package com.safevision.back.infrastructure.web.dto;

import java.util.List;

/**
 * Envoltorio de paginación — {@code data} de {@link ApiEnvelope} para
 * cualquier listado paginado (hoy solo {@code GET /incidents}).
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static <T> PagedResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalItems / size) : 0;
        return new PagedResponse<>(items, page, size, totalItems, totalPages);
    }
}
