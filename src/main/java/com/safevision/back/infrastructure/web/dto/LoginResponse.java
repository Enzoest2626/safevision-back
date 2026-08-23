package com.safevision.back.infrastructure.web.dto;

public record LoginResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        String username,
        String role
) {}
