package com.safevision.back.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("sites")
public record Site(
        @Id Long id,
        String code,
        String name,
        String location,
        Integer cooldownSeconds,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {

    /** Segundos mínimos entre dos alertas de una misma cámara (HU07): default 30 s. */
    public static final int DEFAULT_COOLDOWN_SECONDS = 30;
}
