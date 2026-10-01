package com.safevision.back.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Payload enviado por el módulo de Computer Vision (Python/YOLOv11).
 * Los nombres de campo respetan el contrato snake_case ya acordado con CV.
 */
public record IncidentRequest(
        @JsonProperty("worker_code") @NotNull(message = "worker_code es obligatorio") Integer workerCode,
        @JsonProperty("missing_epp") @NotEmpty(message = "missing_epp no puede estar vacío") List<String> missingEpp,
        @NotNull(message = "timestamp es obligatorio") LocalDateTime timestamp,
        @JsonProperty("camera_code") @NotBlank(message = "camera_code es obligatorio") String cameraCode,
        @JsonProperty("site_name") @NotBlank(message = "site_name es obligatorio") String siteName,
        @JsonProperty("frame_b64") @NotBlank(message = "frame_b64 es obligatorio") String frameB64
) {}
