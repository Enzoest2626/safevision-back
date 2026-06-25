package com.safevision.back.repository;

import com.safevision.back.model.Incident;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface IncidentRepository extends ReactiveCrudRepository<Incident, Long> {

    Flux<Incident> findBySiteId(Long siteId);

    Flux<Incident> findByWorkerId(Long workerId);

    @Query("""
            SELECT * FROM incidents
            WHERE (:siteId   IS NULL OR site_id   = :siteId)
              AND (:workerId IS NULL OR worker_id  = :workerId)
              AND occurred_at BETWEEN :from AND :to
            ORDER BY occurred_at DESC
            """)
    Flux<Incident> findByFilter(Long siteId, Long workerId, LocalDateTime from, LocalDateTime to);
}
