package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.CameraService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CameraRequest;
import com.safevision.back.infrastructure.web.dto.CameraResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cameras")
@Tag(name = "Cameras", description = "Gestión de cámaras IP Hikvision")
public class CameraController {

    private final CameraService cameraService;

    public CameraController(CameraService cameraService) {
        this.cameraService = cameraService;
    }

    @GetMapping
    @Operation(summary = "Listar cámaras activas")
    @ApiResponse(responseCode = "200", description = "Lista de cámaras")
    public Mono<ApiEnvelope<List<CameraResponse>>> findAll() {
        return ApiEnvelope.wrapList(cameraService.findAll(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de cámara")
    @ApiResponse(responseCode = "200", description = "Cámara encontrada")
    @ApiResponse(responseCode = "404", description = "Cámara no encontrada")
    public Mono<ApiEnvelope<CameraResponse>> findById(@PathVariable Long id) {
        return ApiEnvelope.wrap(cameraService.findById(id), HttpStatus.OK);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear cámara", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Cámara creada")
    public Mono<ApiEnvelope<CameraResponse>> create(@Valid @RequestBody CameraRequest request,
                                       @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrap(cameraService.create(request, username), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar cámara", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Cámara actualizada")
    @ApiResponse(responseCode = "404", description = "Cámara no encontrada")
    public Mono<ApiEnvelope<CameraResponse>> update(@PathVariable Long id,
                                       @Valid @RequestBody CameraRequest request,
                                       @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrap(cameraService.update(id, request, username), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar cámara (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Cámara desactivada")
    @ApiResponse(responseCode = "404", description = "Cámara no encontrada")
    public Mono<ApiEnvelope<Void>> delete(@PathVariable Long id,
                             @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrapVoid(cameraService.delete(id, username), HttpStatus.OK);
    }
}
