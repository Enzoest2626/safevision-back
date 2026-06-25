package com.safevision.back.repository;

import com.safevision.back.model.NotificationStatus;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface NotificationStatusRepository extends ReactiveCrudRepository<NotificationStatus, Long> {

    Mono<NotificationStatus> findByCode(String code);
}
