package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.CameraConfigPublisherPort;
import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.web.dto.CameraResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class CameraService {

    private static final Logger log = LoggerFactory.getLogger(CameraService.class);

    private final CameraRepositoryPort cameraRepository;
    private final CameraConfigPublisherPort cameraConfigPublisher;

    public CameraService(CameraRepositoryPort cameraRepository, CameraConfigPublisherPort cameraConfigPublisher) {
        this.cameraRepository = cameraRepository;
        this.cameraConfigPublisher = cameraConfigPublisher;
    }

    /**
     * No notifica nada hasta que la cámara tenga zona asignada (zoneId nulo =
     * registrada pero sin enlazar todavía, ver CLAUDE.md) — el admin tiene
     * que completar site+zone+code antes de habilitar la conexión real.
     */
    private void publishConfig(Camera camera) {
        if (camera.zoneId() == null) {
            log.info("Cámara {} sin zona asignada — no se notifica su config todavía", camera.code());
            return;
        }
        cameraConfigPublisher.publishCameraConfig(camera);
    }

    public Flux<CameraResponse> findAll() {
        return cameraRepository.findByActiveTrue().map(CameraResponse::from);
    }

    public Mono<CameraResponse> findById(Long id) {
        return cameraRepository.findById(id)
                .filter(Camera::active)
                .map(CameraResponse::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Camera not found")));
    }

    public Mono<CameraResponse> create(CameraRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        return resolveCode(request.code())
                .flatMap(code -> {
                    Camera camera = new Camera(null, request.siteId(), request.zoneId(), code, request.name(),
                            request.ipAddress(), request.rtspUrl(), true, now, createdBy, now, createdBy);
                    return cameraRepository.save(camera);
                })
                .doOnNext(this::publishConfig)
                .map(CameraResponse::from);
    }

    public Mono<CameraResponse> update(Long id, CameraRequest request, String updatedBy) {
        return cameraRepository.findById(id)
                .filter(Camera::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Camera not found")))
                .flatMap(existing -> {
                    Camera updated = new Camera(
                            existing.id(), request.siteId(), request.zoneId(), existing.code(), request.name(),
                            request.ipAddress(), request.rtspUrl(), true,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return cameraRepository.save(updated);
                })
                .doOnNext(this::publishConfig)
                .map(CameraResponse::from);
    }

    public Mono<Void> delete(Long id, String updatedBy) {
        return cameraRepository.findById(id)
                .filter(Camera::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Camera not found")))
                .flatMap(existing -> {
                    Camera deactivated = new Camera(
                            existing.id(), existing.siteId(), existing.zoneId(), existing.code(), existing.name(),
                            existing.ipAddress(), existing.rtspUrl(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return cameraRepository.save(deactivated);
                })
                .doOnNext(this::publishConfig)
                .then();
    }

    /**
     * Autogenera el código si el cliente no especificó uno; si lo especificó,
     * valida que no exista ya (único global, mismo criterio que hoy).
     */
    private Mono<String> resolveCode(String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return Mono.just(CodeGenerator.generate("CAM"));
        }
        String trimmed = requestedCode.trim();
        return cameraRepository.existsByCode(trimmed)
                .flatMap(exists -> exists
                        ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Ya existe una cámara con el código '" + trimmed + "'"))
                        : Mono.just(trimmed));
    }
}
