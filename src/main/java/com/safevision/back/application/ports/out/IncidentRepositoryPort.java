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

    @Query("""
            SELECT * FROM incidents
            WHERE (:siteId   IS NULL OR site_id   = :siteId)
              AND (:workerId IS NULL OR worker_id  = :workerId)
              AND occurred_at BETWEEN :from AND :to
            ORDER BY occurred_at DESC
            """)
    Flux<Incident> findByFilter(Long siteId, Long workerId, LocalDateTime from, LocalDateTime to);
}
