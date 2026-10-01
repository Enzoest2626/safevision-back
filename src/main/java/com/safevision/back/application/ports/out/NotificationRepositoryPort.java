package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Notification;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

@NoRepositoryBean
public interface NotificationRepositoryPort extends ReactiveCrudRepository<Notification, Long> {

    Flux<Notification> findByIncidentId(Long incidentId);
}
