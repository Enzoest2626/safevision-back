package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.UserRepositoryPort;
import com.safevision.back.application.ports.out.UserRoleRepositoryPort;
import com.safevision.back.domain.model.User;
import com.safevision.back.infrastructure.security.JwtService;
import com.safevision.back.infrastructure.web.dto.LoginRequest;
import com.safevision.back.infrastructure.web.dto.LoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private final UserRepositoryPort userRepository;
    private final UserRoleRepositoryPort userRoleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepositoryPort userRepository, UserRoleRepositoryPort userRoleRepository,
                       BCryptPasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Mono<LoginResponse> login(LoginRequest request) {
        return userRepository.findByUsername(request.username())
                .filter(User::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS)))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS));
                    }
                    return userRoleRepository.findById(user.roleId())
                            .map(role -> {
                                String token = jwtService.generateToken(user.username(), role.code());
                                return new LoginResponse(token, "Bearer", jwtService.expirationSeconds(),
                                        user.username(), role.code());
                            });
                });
    }
}
