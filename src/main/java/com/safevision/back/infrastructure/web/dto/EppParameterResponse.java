package com.safevision.back.infrastructure.web.dto;

import com.safevision.back.domain.model.EppParameter;

import java.util.List;

/**
 * Respuesta del endpoint GET/PUT /api/v1/parameters/{siteId}.
 * Retorna los EPPs requeridos para una obra específica.
 */
public record EppParameterResponse(
        Long siteId,
        List<EppItem> requiredEpp
) {
    public record EppItem(Long id, String code, String name) {
        public static EppItem from(EppParameter p) {
            return new EppItem(p.id(), p.code(), p.name());
        }
    }
}
