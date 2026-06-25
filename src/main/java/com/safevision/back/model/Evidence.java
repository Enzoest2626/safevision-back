package com.safevision.back.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("evidence")
public record Evidence(
        @Id Long id,
        Long incidentId,
        String frameB64,
        LocalDateTime createdAt
) {}
