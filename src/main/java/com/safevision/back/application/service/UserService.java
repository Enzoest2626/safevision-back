package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.UserRepositoryPort;
import com.safevision.back.application.ports.out.UserRoleRepositoryPort;
import com.safevision.back.domain.model.User;
import com.safevision.back.domain.model.UserRole;
import com.safevision.back.infrastructure.web.dto.UserRequest;
import com.safevision.back.infrastructure.web.dto.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class UserService {

    private final UserRepositoryPort userRepository;
    private final UserRoleRepositoryPort userRoleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepositoryPort userRepository, UserRoleRepositoryPort userRoleRepository,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Flux<UserResponse> findAll() {
        return userRepository.findByActiveTrue().flatMap(this::withRoleCode);
    }

    public Mono<UserResponse> findById(Long id) {
        return userRepository.findById(id)
                .filter(User::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(this::withRoleCode);
    }

    public Mono<UserResponse> create(UserRequest request, String createdBy) {
        return resolveRole(request.roleCode())
                .flatMap(role -> {
                    LocalDateTime now = LocalDateTime.now();
                    String hash = passwordEncoder.encode(request.password());
                    User user = new User(null, request.username(), request.email(), hash,
                            role.id(), request.phone(), true, now, createdBy, now, createdBy);
                    return userRepository.save(user).map(saved -> UserResponse.from(saved, role.code()));
                });
    }

    public Mono<UserResponse> update(Long id, UserRequest request, String updatedBy) {
        return userRepository.findById(id)
                .filter(User::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(existing -> resolveRole(request.roleCode())
                        .flatMap(role -> {
                            String hash = passwordEncoder.encode(request.password());
                            User updated = new User(
                                    existing.id(), request.username(), request.email(), hash,
                                    role.id(), request.phone(), true,
                                    existing.createdAt(), existing.createdBy(),
                                    LocalDateTime.now(), updatedBy
                            );
                            return userRepository.save(updated).map(saved -> UserResponse.from(saved, role.code()));
                        }));
    }

    public Mono<Void> delete(Long id, String updatedBy) {
        return userRepository.findById(id)
                .filter(User::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(existing -> {
                    User deactivated = new User(
                            existing.id(), existing.username(), existing.email(), existing.passwordHash(),
                            existing.roleId(), existing.phone(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return userRepository.save(deactivated);
                })
                .then();
    }

    private Mono<UserResponse> withRoleCode(User user) {
        return userRoleRepository.findById(user.roleId()).map(role -> UserResponse.from(user, role.code()));
    }

    private Mono<UserRole> resolveRole(String roleCode) {
        return userRoleRepository.findByCode(roleCode)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid role: " + roleCode)));
    }
}
