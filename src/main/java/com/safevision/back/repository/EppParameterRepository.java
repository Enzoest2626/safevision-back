package com.safevision.back.repository;

import com.safevision.back.model.EppParameter;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EppParameterRepository extends ReactiveCrudRepository<EppParameter, Long> {

    Flux<EppParameter> findByActiveTrue();

    Mono<EppParameter> findByCode(String code);
}
