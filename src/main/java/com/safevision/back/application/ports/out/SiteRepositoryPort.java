package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Site;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface SiteRepositoryPort extends ReactiveCrudRepository<Site, Long> {

    Flux<Site> findByActiveTrue();

    Mono<Site> findByName(String name);

    Mono<Boolean> existsByCode(String code);
}
