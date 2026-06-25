package com.safevision.back.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(min = 8, max = 300) String password,
        @NotBlank String roleCode,
        @Size(max = 100) String telegramChatId
) {}
