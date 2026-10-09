package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SiteContactRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres") String name,
        @NotBlank(message = "El teléfono es obligatorio")
        // El lookahead acota el teléfono a 20 caracteres EN TOTAL (incluido el
        // '+') — es la longitud de la columna site_contacts.phone (VARCHAR(20)).
        // Sin él, el {7,20} posterior solo mide la parte tras el '+', lo que
        // permitía 21 caracteres totales y la BD los rechazaba con 500.
        @Pattern(regexp = "(?=.{1,20}$)\\+?[0-9\\s\\-]{7,20}",
                message = "El teléfono no tiene un formato válido (ejemplo: +51 999 999 999)") String phone,
        @Size(max = 100, message = "El Chat ID de Telegram no puede superar los 100 caracteres") String telegramChatId
) {}
