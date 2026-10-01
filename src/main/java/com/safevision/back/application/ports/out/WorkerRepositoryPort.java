package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Worker;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface WorkerRepositoryPort extends ReactiveCrudRepository<Worker, Long> {

    Flux<Worker> findByActiveTrue();

    Mono<Worker> findByCode(int code);

    Flux<Worker> findBySiteIdAndActiveTrue(Long siteId);
}
