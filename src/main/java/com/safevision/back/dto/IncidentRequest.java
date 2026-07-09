package com.safevision.back.dto;

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
        @JsonProperty("worker_code") @NotNull Integer workerCode,
        @JsonProperty("missing_epp") @NotEmpty List<String> missingEpp,
        @NotNull LocalDateTime timestamp,
        @JsonProperty("camera_code") @NotBlank String cameraCode,
        @JsonProperty("site_name") @NotBlank String siteName,
        @JsonProperty("frame_b64") @NotBlank String frameB64
) {}
