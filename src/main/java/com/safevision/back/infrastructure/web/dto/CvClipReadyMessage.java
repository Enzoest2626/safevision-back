package com.safevision.back.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload que el módulo CV envía por {@code POST /api/v1/cv/incidents/clips}
 * cuando termina de subir el clip de video de un incidente ya notificado —
 * llega minutos después del mensaje de foto, correlacionado por
 * {@code incident_id}.
 */
public record CvClipReadyMessage(
        @JsonProperty("incident_id") String incidentId,
        @JsonProperty("s3_key") String s3Key,
        @JsonProperty("duration_seconds") Double durationSeconds,
        @JsonProperty("file_size_bytes") Long fileSizeBytes
) {}
