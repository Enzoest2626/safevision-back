package com.safevision.back.repository;

import com.safevision.back.model.Zone;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ZoneRepository extends ReactiveCrudRepository<Zone, Long> {

    Flux<Zone> findBySiteIdAndActiveTrue(Long siteId);
}
