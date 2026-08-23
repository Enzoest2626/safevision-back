package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Zone;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface ZoneRepositoryPort extends ReactiveCrudRepository<Zone, Long> {

    Flux<Zone> findBySiteIdAndActiveTrue(Long siteId);

    Mono<Boolean> existsBySiteIdAndCode(Long siteId, String code);
}
