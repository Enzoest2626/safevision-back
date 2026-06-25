package com.safevision.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SiteRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 200) String location
) {}
