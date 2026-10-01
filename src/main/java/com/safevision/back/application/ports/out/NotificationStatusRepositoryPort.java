package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.NotificationStatus;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface NotificationStatusRepositoryPort extends ReactiveCrudRepository<NotificationStatus, Long> {

    Mono<NotificationStatus> findByCode(String code);
}
