package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.UserRole;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface UserRoleRepositoryPort extends ReactiveCrudRepository<UserRole, Long> {

    Mono<UserRole> findByCode(String code);
}
