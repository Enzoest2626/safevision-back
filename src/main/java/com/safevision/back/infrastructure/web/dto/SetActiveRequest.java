package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;

public record SetActiveRequest(
        @NotNull(message = "active es obligatorio") Boolean active
) {}
