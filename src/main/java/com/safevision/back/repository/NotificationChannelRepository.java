package com.safevision.back.repository;

import com.safevision.back.model.NotificationChannel;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface NotificationChannelRepository extends ReactiveCrudRepository<NotificationChannel, Long> {

    Mono<NotificationChannel> findByCode(String code);
}
