package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.SiteEppRequirement;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface SiteEppRequirementRepositoryPort extends ReactiveCrudRepository<SiteEppRequirement, Long> {

    Flux<SiteEppRequirement> findBySiteId(Long siteId);

    Mono<Long> deleteAllBySiteId(Long siteId);
}
