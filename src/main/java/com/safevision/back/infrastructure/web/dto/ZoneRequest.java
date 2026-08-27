package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ZoneRequest(
        // Solo se usa al crear — autogenerado si se omite, ignorado en
        // el PUT (inmutable, ver ZoneService y CLAUDE.md).
        @Size(max = 50, message = "El código no puede superar los 50 caracteres") String code,
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres") String name
) {}
