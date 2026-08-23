package com.safevision.back.infrastructure.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record EppParameterRequest(
        @NotNull @NotEmpty List<String> requiredEpp
) {}
