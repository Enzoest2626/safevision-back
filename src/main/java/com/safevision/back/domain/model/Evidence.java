package com.safevision.back.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("evidence")
public record Evidence(
        @Id Long id,
        Long incidentId,
        String evidenceType,
        String frameB64,
        String storageKey,
        Double durationSeconds,
        Long fileSizeBytes,
        LocalDateTime createdAt
) {}
