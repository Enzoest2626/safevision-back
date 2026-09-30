package com.safevision.back.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Payload que el módulo CV envía por {@code POST /api/v1/cv/incidents} al
 * confirmarse un incumplimiento — la foto ya está subida a S3, este mensaje
 * solo trae la referencia. Los nombres de campo respetan el contrato
 * snake_case ya acordado con el CV.
 *
 * <p>Mismas validaciones que {@link IncidentRequest} (flujo legacy): sin EPP
 * faltante no hay incumplimiento, así que un {@code missing_epp} vacío se
 * rechaza con 400 antes de registrar ni notificar nada (CP31).
 */
public record CvIncidentMessage(
        @JsonProperty("incident_id") @NotBlank(message = "incident_id es obligatorio")
        @Size(max = 36, message = "incident_id admite hasta 36 caracteres") String incidentId,
        @JsonProperty("worker_code") @NotNull(message = "worker_code es obligatorio") Integer workerCode,
        @JsonProperty("missing_epp") @NotEmpty(message = "missing_epp no puede estar vacío") List<String> missingEpp,
        @NotNull(message = "timestamp es obligatorio") LocalDateTime timestamp,
        @JsonProperty("camera_code") @NotBlank(message = "camera_code es obligatorio") String cameraCode,
        @JsonProperty("site_name") @NotBlank(message = "site_name es obligatorio") String siteName,
        @JsonProperty("photo_s3_key") @NotBlank(message = "photo_s3_key es obligatorio") String photoS3Key
) {}
