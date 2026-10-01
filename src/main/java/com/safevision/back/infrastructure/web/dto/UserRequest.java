package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequest(
        @NotBlank(message = "El usuario es obligatorio")
        @Size(max = 100, message = "El usuario no puede superar los 100 caracteres") String username,
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 200, message = "El correo no puede superar los 200 caracteres") String email,
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 300, message = "La contraseña debe tener al menos 8 caracteres") String password,
        @NotBlank(message = "Debes seleccionar un rol") String roleCode,
        @Size(max = 20, message = "El teléfono no puede superar los 20 caracteres") String phone
) {}
