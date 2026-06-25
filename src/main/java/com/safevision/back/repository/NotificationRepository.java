package com.safevision.back.repository;

import com.safevision.back.model.Notification;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface NotificationRepository extends ReactiveCrudRepository<Notification, Long> {

    Flux<Notification> findByIncidentId(Long incidentId);
}
