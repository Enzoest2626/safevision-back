package com.safevision.back.dto;

import com.safevision.back.model.Zone;

import java.time.LocalDateTime;

public record ZoneResponse(
        Long id,
        Long siteId,
        String name,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static ZoneResponse from(Zone zone) {
        return new ZoneResponse(
                zone.id(), zone.siteId(), zone.name(),
                zone.active(), zone.createdAt(), zone.createdBy(), zone.updatedAt(), zone.updatedBy()
        );
    }
}
