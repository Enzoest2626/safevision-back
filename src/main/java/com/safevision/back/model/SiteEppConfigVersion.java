package com.safevision.back.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Control de versión optimista para la configuración EPP de una obra (CP18,
 * HU04). Una fila por obra, creada perezosamente en el primer PUT.
 */
@Table("site_epp_config_versions")
public record SiteEppConfigVersion(
        @Id Long siteId,
        Long version
) {}
