package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.User;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface UserRepositoryPort extends ReactiveCrudRepository<User, Long> {

    Flux<User> findByActiveTrue();

    Mono<User> findByUsername(String username);

    Mono<User> findByEmail(String email);
}
