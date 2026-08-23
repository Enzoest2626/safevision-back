package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CameraRequest(
        @NotNull Long siteId,
        Long zoneId,
        // Solo se usa al crear — autogenerado si se omite, ignorado en
        // el PUT (inmutable, ver CameraService y CLAUDE.md).
        @Size(max = 50) String code,
        @Size(max = 100) String name,
        @Size(max = 50) String ipAddress,
        @Size(max = 300) String rtspUrl
) {}
