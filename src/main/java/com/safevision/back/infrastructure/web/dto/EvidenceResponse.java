package com.safevision.back.infrastructure.web.dto;

import com.safevision.back.domain.model.Evidence;

import java.time.LocalDateTime;

/**
 * Respuesta del endpoint GET /api/v1/incidents/{id}/evidence — una fila por
 * cada foto/clip del incidente. {@code url} es una URL prefirmada de S3
 * (corta duración) cuando la evidencia tiene {@code storageKey}; queda
 * {@code null} para filas legacy que solo tienen {@code frameB64} inline.
 */
public record EvidenceResponse(
        String evidenceType,
        String url,
        Double durationSeconds,
        Long fileSizeBytes,
        LocalDateTime createdAt
) {
    public static EvidenceResponse from(Evidence evidence, String url) {
        return new EvidenceResponse(evidence.evidenceType(), url, evidence.durationSeconds(),
                evidence.fileSizeBytes(), evidence.createdAt());
    }
}
