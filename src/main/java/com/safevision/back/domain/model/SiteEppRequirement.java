package com.safevision.back.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("site_epp_requirements")
public record SiteEppRequirement(
        @Id Long id,
        Long siteId,
        Long eppParameterId,
        LocalDateTime updatedAt,
        String updatedBy
) {}
