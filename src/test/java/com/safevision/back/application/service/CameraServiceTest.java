package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.safevision.back.application.ports.out.CameraConfigPublisherPort;
import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.application.ports.out.ZoneRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Zone;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
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
    private CameraRepositoryPort cameraRepository;

    @Mock
    private SiteRepositoryPort siteRepository;

    @Mock
    private ZoneRepositoryPort zoneRepository;

    @Mock
    private CameraConfigPublisherPort cameraConfigPublisher;

    private CameraService service;

    private final Site site = new Site(10L, "OBRA-A", "Obra A", null, true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final Zone zone = new Zone(20L, 10L, "ZONA-A", "Zona A", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    // zoneId=20L (enlazada) — la mayoría de los tests verifican que se
    // publica; los que necesitan zoneId=null (sin enlazar) usan su propio fixture.
    private final Camera camera = new Camera(1L, 10L, 20L, "CAM-01", "Entrada",
            "10.0.0.5", null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private final CameraRequest request = new CameraRequest(10L, 20L, "CAM-01", "Entrada", "10.0.0.5", null);

    @BeforeEach
    void setUp() {
        service = new CameraService(cameraRepository, siteRepository, zoneRepository, cameraConfigPublisher);
        lenient().when(siteRepository.findById(10L)).thenReturn(Mono.just(site));
        lenient().when(zoneRepository.findById(20L)).thenReturn(Mono.just(zone));
        lenient().when(cameraRepository.existsByCode(anyString())).thenReturn(Mono.just(false));
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
        Camera inactiva = new Camera(1L, 10L, 20L, "CAM-01", "Entrada",
                "10.0.0.5", null, false, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(inactiva));

        StepVerifier.create(service.findById(1L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("create persiste la cámara nueva y publica su config MQTT")
    void create_persisteCamara() {
        when(cameraRepository.save(any(Camera.class))).thenReturn(Mono.just(camera));

        StepVerifier.create(service.create(request, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-01"))
                .verifyComplete();

        verify(cameraRepository).save(any(Camera.class));
        verify(cameraConfigPublisher).publishCameraConfig("OBRA-A", "ZONA-A", "CAM-01", null, true);
    }

    @Test
    @DisplayName("create con código duplicado lanza 409")
    void create_codigoDuplicado_lanzaConflict() {
        when(cameraRepository.existsByCode("CAM-01")).thenReturn(Mono.just(true));

        StepVerifier.create(service.create(request, "supervisor1"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.CONFLICT)
                .verify();

        verify(cameraRepository, never()).save(any());
    }

    @Test
    @DisplayName("create sin código lo autogenera")
    void create_sinCodigo_autogenera() {
        CameraRequest sinCodigo = new CameraRequest(10L, 20L, null, "Entrada", "10.0.0.5", null);
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.create(sinCodigo, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).startsWith("CAM-"))
                .verifyComplete();

        verify(cameraRepository, never()).existsByCode(any());
    }

    @Test
    @DisplayName("create sin zona no publica config MQTT — cámara sin enlazar todavía")
    void create_sinZona_noPublica() {
        CameraRequest sinZona = new CameraRequest(10L, null, "CAM-02", "Entrada", "10.0.0.5", null);
        Camera guardada = new Camera(2L, 10L, null, "CAM-02", "Entrada",
                "10.0.0.5", null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(cameraRepository.save(any(Camera.class))).thenReturn(Mono.just(guardada));

        StepVerifier.create(service.create(sinZona, "supervisor1"))
                .assertNext(response -> assertThat(response.zoneId()).isNull())
                .verifyComplete();

        verifyNoInteractions(cameraConfigPublisher);
    }

    @Test
    @DisplayName("update sobre cámara existente persiste cambios y publica su config MQTT")
    void update_existente_persisteCambios() {
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(camera));
        when(cameraRepository.save(any(Camera.class))).thenReturn(Mono.just(camera));

        StepVerifier.create(service.update(1L, request, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-01"))
                .verifyComplete();

        verify(cameraConfigPublisher).publishCameraConfig("OBRA-A", "ZONA-A", "CAM-01", null, true);
    }

    @Test
    @DisplayName("update ignora el código del request — es inmutable tras la creación")
    void update_conservaCodigoOriginal() {
        CameraRequest conOtroCodigo = new CameraRequest(10L, 20L, "CAM-OTRO", "Entrada", "10.0.0.5", null);
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(camera));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.update(1L, conOtroCodigo, "supervisor1"))
                .assertNext(response -> assertThat(response.code()).isEqualTo("CAM-01"))
                .verifyComplete();

        verify(cameraRepository).save(argThat(c -> c.code().equals("CAM-01")));
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
    @DisplayName("delete desactiva la cámara (soft delete) y publica active=false")
    void delete_existente_desactiva() {
        when(cameraRepository.findById(1L)).thenReturn(Mono.just(camera));
        // Devuelve el mismo objeto guardado (no el fixture fijo) para poder
        // verificar que publishConfig recibe active=false, no el active=true
        // del fixture original.
        when(cameraRepository.save(any(Camera.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.delete(1L, "supervisor1"))
                .verifyComplete();

        verify(cameraRepository).save(argThat(c -> !c.active()));
        verify(cameraConfigPublisher).publishCameraConfig("OBRA-A", "ZONA-A", "CAM-01", null, false);
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
