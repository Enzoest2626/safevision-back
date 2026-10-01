package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Evidence;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

@NoRepositoryBean
public interface EvidenceRepositoryPort extends ReactiveCrudRepository<Evidence, Long> {

    /** Un incidente puede tener varias filas de evidencia (foto + clip de video). */
    Flux<Evidence> findByIncidentId(Long incidentId);
}
