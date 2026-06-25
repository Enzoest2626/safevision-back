package com.safevision.back.repository;

import com.safevision.back.model.UserRole;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserRoleRepository extends ReactiveCrudRepository<UserRole, Long> {

    Mono<UserRole> findByCode(String code);
}
