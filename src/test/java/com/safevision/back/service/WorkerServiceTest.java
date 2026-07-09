package com.safevision.back.service;

import com.safevision.back.dto.WorkerRequest;
import com.safevision.back.model.Worker;
import com.safevision.back.repository.WorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerService — CRUD de trabajadores")
class WorkerServiceTest {

    @Mock
    private WorkerRepository workerRepository;

    private WorkerService service;

    private final Worker worker = new Worker(1L, 10L, 3, "Juan", "Perez", "Albañil",
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final WorkerRequest request = new WorkerRequest(10L, 3, "Juan", "Perez", "Albañil");

    @BeforeEach
    void setUp() {
        service = new WorkerService(workerRepository);
    }

    @Test
    @DisplayName("findAll retorna solo trabajadores activos")
    void findAll_retornaActivos() {
        when(workerRepository.findByActiveTrue()).thenReturn(Flux.just(worker));

        StepVerifier.create(service.findAll())
                .assertNext(response -> assertThat(response.firstName()).isEqualTo("Juan"))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById existente retorna el trabajador")
    void findById_existente_retornaTrabajador() {
        when(workerRepository.findById(1L)).thenReturn(Mono.just(worker));

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById inexistente lanza 404")
    void findById_inexistente_lanzaNotFound() {
        when(workerRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(99L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("create persiste el trabajador nuevo")
    void create_persisteTrabajador() {
        when(workerRepository.save(any(Worker.class))).thenReturn(Mono.just(worker));

        StepVerifier.create(service.create(request, "supervisor1"))
                .assertNext(response -> assertThat(response.firstName()).isEqualTo("Juan"))
                .verifyComplete();

        verify(workerRepository).save(any(Worker.class));
    }

    @Test
    @DisplayName("update sobre trabajador existente persiste cambios")
    void update_existente_persisteCambios() {
        when(workerRepository.findById(1L)).thenReturn(Mono.just(worker));
        when(workerRepository.save(any(Worker.class))).thenReturn(Mono.just(worker));

        StepVerifier.create(service.update(1L, request, "supervisor1"))
                .assertNext(response -> assertThat(response.firstName()).isEqualTo("Juan"))
                .verifyComplete();
    }

    @Test
    @DisplayName("update sobre trabajador inexistente lanza 404")
    void update_inexistente_lanzaNotFound() {
        when(workerRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(workerRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete desactiva el trabajador (soft delete)")
    void delete_existente_desactiva() {
        when(workerRepository.findById(1L)).thenReturn(Mono.just(worker));
        when(workerRepository.save(any(Worker.class))).thenReturn(Mono.just(worker));

        StepVerifier.create(service.delete(1L, "supervisor1"))
                .verifyComplete();

        verify(workerRepository).save(argThat(w -> !w.active()));
    }

    @Test
    @DisplayName("delete sobre trabajador inexistente lanza 404")
    void delete_inexistente_lanzaNotFound() {
        when(workerRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(99L, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(workerRepository, never()).save(any());
    }
}
