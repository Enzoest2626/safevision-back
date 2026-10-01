package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.SiteEppConfigVersion;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

/**
 * Control de versión optimista para la configuración EPP de una obra.
 * ensureExists + compareAndSwap implementan un compare-and-swap
 * atómico a nivel de fila: si dos PUT concurrentes leen la misma versión,
 * solo uno logra el CAS — el otro recibe 0 filas afectadas y el service
 * responde 409 CONFLICT.
 */
@NoRepositoryBean
public interface SiteEppConfigVersionRepositoryPort extends ReactiveCrudRepository<SiteEppConfigVersion, Long> {

    @Query("INSERT INTO site_epp_config_versions (site_id, version) VALUES (:siteId, 0) "
            + "ON CONFLICT (site_id) DO NOTHING")
    Mono<Void> ensureExists(Long siteId);

    @Query("UPDATE site_epp_config_versions SET version = version + 1 "
            + "WHERE site_id = :siteId AND version = :expectedVersion RETURNING version")
    Mono<Long> compareAndSwap(Long siteId, Long expectedVersion);
}
