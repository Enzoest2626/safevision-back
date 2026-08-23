package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ZoneRequest(
        // Solo se usa al crear — autogenerado si se omite, ignorado en
        // el PUT (inmutable, ver ZoneService y CLAUDE.md).
        @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name
) {}
