package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record WorkerRequest(
        @NotNull(message = "Debes seleccionar una obra") Long siteId,
        @Positive(message = "El código debe ser un número positivo") int code,
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres") String firstName,
        @NotBlank(message = "El apellido es obligatorio")
        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres") String lastName,
        @Size(max = 100, message = "El rol no puede superar los 100 caracteres") String role
) {}
