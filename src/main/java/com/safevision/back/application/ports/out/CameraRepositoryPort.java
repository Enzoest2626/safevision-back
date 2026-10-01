package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Camera;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface CameraRepositoryPort extends ReactiveCrudRepository<Camera, Long> {

    Flux<Camera> findByActiveTrue();

    Mono<Camera> findByCode(String code);

    Mono<Boolean> existsByCode(String code);

    Flux<Camera> findBySiteIdAndActiveTrue(Long siteId);
}
