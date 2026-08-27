package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Incident;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@NoRepositoryBean
public interface IncidentRepositoryPort extends ReactiveCrudRepository<Incident, Long> {

    Flux<Incident> findBySiteId(Long siteId);

    Flux<Incident> findByWorkerId(Long workerId);

    /** Correlaciona el aviso de "clip listo" con el incidente ya persistido. */
    Mono<Incident> findByExternalId(String externalId);

    /**
     * Misma condición para todo GET /incidents, una página a la vez — R2DBC
     * no traduce {@code Pageable} dentro de un {@code @Query} propio como sí
     * hace JPA, así que el LIMIT/OFFSET va explícito acá.
     */
    @Query("""
            SELECT * FROM incidents
            WHERE (:siteId   IS NULL OR site_id   = :siteId)
              AND (:workerId IS NULL OR worker_id  = :workerId)
              AND occurred_at BETWEEN :from AND :to
            ORDER BY occurred_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Incident> findByFilterPaged(Long siteId, Long workerId, LocalDateTime from, LocalDateTime to,
                                      int limit, long offset);

    /** Total de filas que matchean el mismo filtro — para calcular totalPages en el front. */
    @Query("""
            SELECT COUNT(*) FROM incidents
            WHERE (:siteId   IS NULL OR site_id   = :siteId)
              AND (:workerId IS NULL OR worker_id  = :workerId)
              AND occurred_at BETWEEN :from AND :to
            """)
    Mono<Long> countByFilter(Long siteId, Long workerId, LocalDateTime from, LocalDateTime to);
}
