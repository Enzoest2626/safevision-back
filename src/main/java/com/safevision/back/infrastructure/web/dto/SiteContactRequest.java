package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SiteContactRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "\\+?[0-9\\s\\-]{7,20}") String phone,
        @Size(max = 100) String telegramChatId
) {}
