package com.safevision.back.repository;

import com.safevision.back.model.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    Flux<User> findByActiveTrue();

    Mono<User> findByUsername(String username);

    Mono<User> findByEmail(String email);
}
