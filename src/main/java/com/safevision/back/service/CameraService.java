package com.safevision.back.service;

import com.safevision.back.dto.CameraRequest;
import com.safevision.back.dto.CameraResponse;
import com.safevision.back.model.Camera;
import com.safevision.back.repository.CameraRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class CameraService {

    private final CameraRepository cameraRepository;

    public CameraService(CameraRepository cameraRepository) {
        this.cameraRepository = cameraRepository;
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
        Camera camera = new Camera(null, request.siteId(), request.zoneId(), request.code(), request.name(),
                request.ipAddress(), request.rtspUrl(), true, now, createdBy, now, createdBy);
        return cameraRepository.save(camera).map(CameraResponse::from);
    }

    public Mono<CameraResponse> update(Long id, CameraRequest request, String updatedBy) {
        return cameraRepository.findById(id)
                .filter(Camera::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Camera not found")))
                .flatMap(existing -> {
                    Camera updated = new Camera(
                            existing.id(), request.siteId(), request.zoneId(), request.code(), request.name(),
                            request.ipAddress(), request.rtspUrl(), true,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return cameraRepository.save(updated);
                })
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
                .then();
    }
}
