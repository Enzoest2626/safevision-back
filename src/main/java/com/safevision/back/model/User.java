package com.safevision.back.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("users")
public record User(
        @Id Long id,
        String username,
        String email,
        String passwordHash,
        Long roleId,
        String phone,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {}
