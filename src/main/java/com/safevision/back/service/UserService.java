package com.safevision.back.service;

import com.safevision.back.dto.UserRequest;
import com.safevision.back.dto.UserResponse;
import com.safevision.back.model.User;
import com.safevision.back.repository.UserRepository;
import com.safevision.back.repository.UserRoleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, UserRoleRepository userRoleRepository,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Flux<UserResponse> findAll() {
        return userRepository.findByActiveTrue().map(UserResponse::from);
    }

    public Mono<UserResponse> findById(Long id) {
        return userRepository.findById(id)
                .filter(User::active)
                .map(UserResponse::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    }

    public Mono<UserResponse> create(UserRequest request, String createdBy) {
        return userRoleRepository.findByCode(request.roleCode())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role: " + request.roleCode())))
                .flatMap(role -> {
                    LocalDateTime now = LocalDateTime.now();
                    String hash = passwordEncoder.encode(request.password());
                    User user = new User(null, request.username(), request.email(), hash,
                            role.id(), request.telegramChatId(), true, now, createdBy, now, createdBy);
                    return userRepository.save(user);
                })
                .map(UserResponse::from);
    }

    public Mono<UserResponse> update(Long id, UserRequest request, String updatedBy) {
        return userRepository.findById(id)
                .filter(User::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(existing -> userRoleRepository.findByCode(request.roleCode())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role: " + request.roleCode())))
                        .flatMap(role -> {
                            String hash = passwordEncoder.encode(request.password());
                            User updated = new User(
                                    existing.id(), request.username(), request.email(), hash,
                                    role.id(), request.telegramChatId(), true,
                                    existing.createdAt(), existing.createdBy(),
                                    LocalDateTime.now(), updatedBy
                            );
                            return userRepository.save(updated);
                        }))
                .map(UserResponse::from);
    }

    public Mono<Void> delete(Long id, String updatedBy) {
        return userRepository.findById(id)
                .filter(User::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(existing -> {
                    User deactivated = new User(
                            existing.id(), existing.username(), existing.email(), existing.passwordHash(),
                            existing.roleId(), existing.telegramChatId(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return userRepository.save(deactivated);
                })
                .then();
    }
}
