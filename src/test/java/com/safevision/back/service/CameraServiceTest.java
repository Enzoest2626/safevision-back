package com.safevision.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.safevision.back.dto.CameraRequest;
import com.safevision.back.model.Camera;
import com.safevision.back.repository.CameraRepository;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("CameraService — CRUD de cámaras")
class CameraServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    private CameraService service;

    private final Camera camera = new Camera(1L, 10L, null, "CAM-01", "Entrada",
            "10.0.0.5", null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final CameraRequest request = new CameraRequest(10L, null, "CAM-01", "Entrada", "10.0.0.5", null);

    @BeforeEach
    void setUp() {
        service = new CameraService(cameraRepository);
    }

    @Test
    @DisplayName("findAll retorna solo cámaras activas")
    void findAll_retornaActivas() {
        when(cameraRepository.findByActiveTrue()).thenReturn(Flux.just(camera));

        StepVerifier.create(service.findAll())
                .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-01"))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById existente retorna la cámara")
    void findById_existente_retornaCamara() {
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(camera));

        StepVerifier.create(service.findById(1L))
                .assertNext(response -> assertThat(response.id()).isEqualTo(1L))
                .verifyComplete();
    }

    @Test
    @DisplayName("findById inexistente lanza 404")
    void findById_inexistente_lanzaNotFound() {
        when(cameraRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(99L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("findById inactiva lanza 404")
    void findById_inactiva_lanzaNotFound() {
        Camera inactiva = new Camera(1L, 10L, null, "CAM-01", "Entrada",
                "10.0.0.5", null, false, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(inactiva));

        StepVerifier.create(service.findById(1L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("create persiste la cámara nueva")
    void create_persisteCamara() {
        when(cameraRepository.save(any(Camera.class))).thenReturn(Mono.just(camera));

        StepVerifier.create(service.create(request, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-01"))
                .verifyComplete();

        verify(cameraRepository).save(any(Camera.class));
    }

    @Test
    @DisplayName("update sobre cámara existente persiste cambios")
    void update_existente_persisteCambios() {
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(camera));
        when(cameraRepository.save(any(Camera.class))).thenReturn(Mono.just(camera));

        StepVerifier.create(service.update(1L, request, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-01"))
                .verifyComplete();
    }

    @Test
    @DisplayName("update sobre cámara inexistente lanza 404")
    void update_inexistente_lanzaNotFound() {
        when(cameraRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(99L, request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(cameraRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete desactiva la cámara (soft delete)")
    void delete_existente_desactiva() {
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(camera));
        when(cameraRepository.save(any(Camera.class))).thenReturn(Mono.just(camera));

        StepVerifier.create(service.delete(1L, "supervisor1"))
                .verifyComplete();

        verify(cameraRepository).save(argThat(c -> !c.active()));
    }

    @Test
    @DisplayName("delete sobre cámara inexistente lanza 404")
    void delete_inexistente_lanzaNotFound() {
        when(cameraRepository.findById(99L)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(99L, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verify(cameraRepository, never()).save(any());
    }
}
