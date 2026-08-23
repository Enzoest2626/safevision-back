package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.NotificationChannel;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface NotificationChannelRepositoryPort extends ReactiveCrudRepository<NotificationChannel, Long> {

    Mono<NotificationChannel> findByCode(String code);
}
