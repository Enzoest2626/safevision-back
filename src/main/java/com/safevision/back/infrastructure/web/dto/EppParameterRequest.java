package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record EppParameterRequest(
        @NotNull(message = "Debes seleccionar al menos un EPP requerido")
        @NotEmpty(message = "Debes seleccionar al menos un EPP requerido") List<String> requiredEpp,
        @NotNull(message = "El cooldown es obligatorio")
        @Min(value = 1, message = "El cooldown debe ser de al menos 1 segundo") Integer cooldownSeconds
) {}
