package com.safevision.back.repository;

import com.safevision.back.model.Site;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SiteRepository extends ReactiveCrudRepository<Site, Long> {

    Flux<Site> findByActiveTrue();

    Mono<Site> findByName(String name);
}
