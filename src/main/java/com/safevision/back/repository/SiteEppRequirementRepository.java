package com.safevision.back.repository;

import com.safevision.back.model.SiteEppRequirement;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SiteEppRequirementRepository extends ReactiveCrudRepository<SiteEppRequirement, Long> {

    Flux<SiteEppRequirement> findBySiteId(Long siteId);

    Mono<Long> deleteAllBySiteId(Long siteId);
}
