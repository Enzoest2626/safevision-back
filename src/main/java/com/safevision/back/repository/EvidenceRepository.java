package com.safevision.back.repository;

import com.safevision.back.model.Evidence;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface EvidenceRepository extends ReactiveCrudRepository<Evidence, Long> {

    Mono<Evidence> findByIncidentId(Long incidentId);
}
