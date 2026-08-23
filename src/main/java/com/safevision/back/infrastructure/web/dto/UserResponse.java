package com.safevision.back.infrastructure.web.dto;

import com.safevision.back.domain.model.User;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String username,
        String email,
        Long roleId,
        String roleCode,
        String phone,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static UserResponse from(User user, String roleCode) {
        return new UserResponse(
                user.id(), user.username(), user.email(), user.roleId(), roleCode,
                user.phone(), user.active(),
                user.createdAt(), user.createdBy(), user.updatedAt(), user.updatedBy()
        );
    }
}
