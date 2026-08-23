package com.safevision.back.infrastructure.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mensaje MQTT publicado por el módulo CV en {@code safevision/{siteId}/incidents}
 * al confirmarse un incumplimiento — la foto ya está subida a S3, este mensaje
 * solo trae la referencia. Los nombres de campo respetan el contrato snake_case
 * ya acordado con el CV.
 */
public record CvIncidentMessage(
        @JsonProperty("incident_id") String incidentId,
        @JsonProperty("worker_code") Integer workerCode,
        @JsonProperty("missing_epp") List<String> missingEpp,
        LocalDateTime timestamp,
        @JsonProperty("camera_code") String cameraCode,
        @JsonProperty("site_name") String siteName,
        @JsonProperty("photo_s3_key") String photoS3Key
) {}
