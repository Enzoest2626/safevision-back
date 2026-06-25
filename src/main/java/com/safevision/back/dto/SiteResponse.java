package com.safevision.back.dto;

import com.safevision.back.model.Site;

import java.time.LocalDateTime;

public record SiteResponse(
        Long id,
        String name,
        String location,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static SiteResponse from(Site site) {
        return new SiteResponse(
                site.id(), site.name(), site.location(), site.active(),
                site.createdAt(), site.createdBy(), site.updatedAt(), site.updatedBy()
        );
    }
}
