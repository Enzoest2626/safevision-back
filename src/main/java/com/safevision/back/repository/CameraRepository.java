package com.safevision.back.repository;

import com.safevision.back.model.Camera;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CameraRepository extends ReactiveCrudRepository<Camera, Long> {

    Flux<Camera> findByActiveTrue();

    Mono<Camera> findByCode(String code);

    Flux<Camera> findBySiteIdAndActiveTrue(Long siteId);
}
