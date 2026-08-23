package com.safevision.back.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("incidents")
public record Incident(
        @Id Long id,
        Long workerId,
        Long cameraId,
        Long siteId,
        String externalId,
        String[] missingEpp,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
) {}
