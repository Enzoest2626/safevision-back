package com.safevision.back.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("incidents")
public record Incident(
        @Id Long id,
        Long workerId,
        Long cameraId,
        Long siteId,
        String[] missingEpp,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {}
