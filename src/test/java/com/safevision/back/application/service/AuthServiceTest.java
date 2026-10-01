package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.UserRepositoryPort;
import com.safevision.back.application.ports.out.UserRoleRepositoryPort;
import com.safevision.back.domain.model.User;
import com.safevision.back.domain.model.UserRole;
import com.safevision.back.infrastructure.security.JwtService;
import com.safevision.back.infrastructure.web.dto.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — login de usuarios")
class AuthServiceTest {

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private UserRoleRepositoryPort userRoleRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService =
            new JwtService("test-jwt-secret-safevision-minimum-32-bytes-long", 480);

    private AuthService service;

    private final UserRole supervisorRole = new UserRole(1L, "SUPERVISOR", "Supervisor");

    private User activeUser;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, userRoleRepository, passwordEncoder, jwtService);
        activeUser = new User(1L, "jperez", "jperez@safevision.com",
                passwordEncoder.encode("correct-password"), 1L, "999999999", true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @Test
    @DisplayName("login con credenciales correctas retorna un token")
    void login_credencialesCorrectas_retornaToken() {
        when(userRepository.findByUsername("jperez")).thenReturn(Mono.just(activeUser));
        when(userRoleRepository.findById(1L)).thenReturn(Mono.just(supervisorRole));

        StepVerifier.create(service.login(new LoginRequest("jperez", "correct-password")))
                .assertNext(response -> {
                    assertThat(response.username()).isEqualTo("jperez");
                    assertThat(response.role()).isEqualTo("SUPERVISOR");
                    assertThat(response.tokenType()).isEqualTo("Bearer");
                    assertThat(jwtService.isValid(response.token())).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("login con contraseña incorrecta lanza 401")
    void login_passwordIncorrecta_lanza401() {
        when(userRepository.findByUsername("jperez")).thenReturn(Mono.just(activeUser));

        StepVerifier.create(service.login(new LoginRequest("jperez", "wrong-password")))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verify();
    }

    @Test
    @DisplayName("login con usuario inexistente lanza 401")
    void login_usuarioInexistente_lanza401() {
        when(userRepository.findByUsername("desconocido")).thenReturn(Mono.empty());

        StepVerifier.create(service.login(new LoginRequest("desconocido", "cualquiera")))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verify();
    }

    @Test
    @DisplayName("login con usuario desactivado lanza 401")
    void login_usuarioDesactivado_lanza401() {
        User inactiveUser = new User(2L, "inactivo", "inactivo@safevision.com",
                passwordEncoder.encode("correct-password"), 1L, "999999999", false,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(userRepository.findByUsername("inactivo")).thenReturn(Mono.just(inactiveUser));

        StepVerifier.create(service.login(new LoginRequest("inactivo", "correct-password")))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.UNAUTHORIZED)
                .verify();
    }
}
