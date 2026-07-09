package com.safevision.back.dto;

import com.safevision.back.model.User;

import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String username,
        String email,
        Long roleId,
        String phone,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(), user.username(), user.email(), user.roleId(),
                user.phone(), user.active(),
                user.createdAt(), user.createdBy(), user.updatedAt(), user.updatedBy()
        );
    }
}
