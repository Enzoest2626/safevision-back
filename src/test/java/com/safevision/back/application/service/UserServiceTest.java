package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.safevision.back.application.ports.out.UserRepositoryPort;
import com.safevision.back.application.ports.out.UserRoleRepositoryPort;
import com.safevision.back.domain.model.User;
import com.safevision.back.domain.model.UserRole;
import com.safevision.back.infrastructure.web.dto.UserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — CRUD de usuarios del sistema")
class UserServiceTest {

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private UserRoleRepositoryPort userRoleRepository;

    private UserService service;

    private final UserRole supervisorRole = new UserRole(1L, "SUPERVISOR", "Supervisor");

    private final User user = new User(1L, "jperez", "jperez@safevision.com", "hashed-password",
            1L, "999999999", true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final UserRequest request = new UserRequest("jperez", "jperez@safevision.com",
            "password123", "SUPERVISOR", "999999999");

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, userRoleRepository, new BCryptPasswordEncoder());
    }

    @Test
    @DisplayName("findAll retorna solo usuarios activos")
    void findAll_retornaActivos() {
        when(userRepository.findByActiveTrue()).thenReturn(Flux.just(user));
        when(userRoleRepository.findById(1L)).thenReturn(Mono.just(supervisorRole));

        StepVerifier.create(service.findAll())
                .assertNext(response -> {
                    assertThat(response.username()).isEqualTo("jperez");
                    assertThat(response.roleCode()).isEqualTo("SUPERVISOR");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findById existente retorna el usuario")
    void findById_existente_retornaUsuario() {
        when(userRepository.findById(1L)).thenReturn(Mono.just(user));
        when(userRoleRepository.findById(1L)).thenReturn(Mono.just(supervisorRole));

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById inexistente lanza 404")
    void findById_inexistente_lanzaNotFound() {
        when(userRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(99L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("create con rol válido persiste el usuario nuevo")
    void create_rolValido_persisteUsuario() {
        when(userRoleRepository.findByCode("SUPERVISOR")).thenReturn(Mono.just(supervisorRole));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(user));

        StepVerifier.create(service.create(request, "admin1"))
                .assertNext(response -> assertThat(response.username()).isEqualTo("jperez"))
                .verifyComplete();

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("create con rol inexistente lanza 400")
    void create_rolInexistente_lanzaBadRequest() {
        when(userRoleRepository.findByCode("SUPERVISOR")).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request, "admin1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("update sobre usuario existente persiste cambios")
    void update_existente_persisteCambios() {
        when(userRepository.findById(1L)).thenReturn(Mono.just(user));
        when(userRoleRepository.findByCode("SUPERVISOR")).thenReturn(Mono.just(supervisorRole));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(user));

        StepVerifier.create(service.update(1L, request, "admin1"))
                .assertNext(response -> assertThat(response.username()).isEqualTo("jperez"))
                .verifyComplete();
    }

    @Test
    @DisplayName("update sobre usuario inexistente lanza 404")
    void update_inexistente_lanzaNotFound() {
        when(userRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, request, "admin1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete desactiva el usuario (soft delete)")
    void delete_existente_desactiva() {
        when(userRepository.findById(1L)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(user));

        StepVerifier.create(service.delete(1L, "admin1"))
                .verifyComplete();

        verify(userRepository).save(argThat(u -> !u.active()));
    }

    @Test
    @DisplayName("delete sobre usuario inexistente lanza 404")
    void delete_inexistente_lanzaNotFound() {
        when(userRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(99L, "admin1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(userRepository, never()).save(any());
    }
}
