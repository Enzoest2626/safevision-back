package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.EppParameter;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface EppParameterRepositoryPort extends ReactiveCrudRepository<EppParameter, Long> {

    Flux<EppParameter> findByActiveTrue();

    Mono<EppParameter> findByCode(String code);
}
