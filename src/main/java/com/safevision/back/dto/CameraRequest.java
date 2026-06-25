package com.safevision.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CameraRequest(
        @NotNull Long siteId,
        @NotBlank @Size(max = 50) String code,
        @Size(max = 100) String name,
        @Size(max = 50) String ipAddress,
        @Size(max = 300) String rtspUrl
) {}
