package com.safevision.back.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("cameras")
public record Camera(
        @Id Long id,
        Long siteId,
        Long zoneId,
        String code,
        String name,
        String ipAddress,
        String rtspUrl,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {}
