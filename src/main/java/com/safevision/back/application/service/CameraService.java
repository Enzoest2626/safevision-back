package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.CameraConfigPublisherPort;
import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.application.ports.out.ZoneRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Zone;
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
    private final SiteRepositoryPort siteRepository;
    private final ZoneRepositoryPort zoneRepository;
    private final CameraConfigPublisherPort cameraConfigPublisher;

    public CameraService(CameraRepositoryPort cameraRepository, SiteRepositoryPort siteRepository,
                          ZoneRepositoryPort zoneRepository, CameraConfigPublisherPort cameraConfigPublisher) {
        this.cameraRepository = cameraRepository;
        this.siteRepository = siteRepository;
        this.zoneRepository = zoneRepository;
        this.cameraConfigPublisher = cameraConfigPublisher;
    }

    /**
     * No publica nada hasta que la cámara tenga obra+zona+código completos
     * (zoneId nulo = registrada pero sin enlazar todavía, ver CLAUDE.md) —
     * el topic MQTT necesita los 3 códigos para armarse.
     */
    private Mono<Void> publishConfig(Camera camera) {
        if (camera.zoneId() == null) {
            log.info("Cámara {} sin zona asignada — no se publica config MQTT todavía", camera.code());
            return Mono.empty();
        }
        return Mono.zip(
                        siteRepository.findById(camera.siteId()).map(Site::code),
                        zoneRepository.findById(camera.zoneId()).map(Zone::code))
                .doOnNext(codes -> cameraConfigPublisher.publishCameraConfig(
                        codes.getT1(), codes.getT2(), camera.code(), camera.rtspUrl(), camera.active()))
                .then();
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
                .flatMap(saved -> publishConfig(saved).thenReturn(saved))
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
                .flatMap(saved -> publishConfig(saved).thenReturn(saved))
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
                .flatMap(this::publishConfig);
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
