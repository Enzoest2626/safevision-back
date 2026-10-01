package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CameraRequest(
        @NotNull(message = "Debes seleccionar una obra") Long siteId,
        Long zoneId,
        // Solo se usa al crear — autogenerado si se omite, ignorado en
        // el PUT (inmutable, ver CameraService y CLAUDE.md).
        @Size(max = 50, message = "El código no puede superar los 50 caracteres") String code,
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres") String name,
        @Size(max = 50, message = "La dirección IP no puede superar los 50 caracteres") String ipAddress,
        @Size(max = 300, message = "La URL RTSP no puede superar los 300 caracteres") String rtspUrl
) {}
