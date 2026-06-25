package com.safevision.back.repository;

import com.safevision.back.model.Worker;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface WorkerRepository extends ReactiveCrudRepository<Worker, Long> {

    Flux<Worker> findByActiveTrue();

    Mono<Worker> findByCode(int code);

    Flux<Worker> findBySiteIdAndActiveTrue(Long siteId);
}
