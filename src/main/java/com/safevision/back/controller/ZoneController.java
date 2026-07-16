package com.safevision.back.controller;

import com.safevision.back.dto.ZoneRequest;
import com.safevision.back.dto.ZoneResponse;
import com.safevision.back.service.ZoneService;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/zones")
@Tag(name = "Zones", description = "Zonas dentro de una obra — agrupan cámaras")
public class ZoneController {

    private final ZoneService service;

    public ZoneController(ZoneService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Listar zonas activas de una obra")
    @ApiResponse(responseCode = "200", description = "Lista de zonas")
    public Flux<ZoneResponse> findBySite(@PathVariable Long siteId) {
        return service.findBySite(siteId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear zona en la obra", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Zona creada")
    public Mono<ZoneResponse> create(
            @PathVariable Long siteId,
            @Valid @RequestBody ZoneRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return service.create(siteId, request, username);
    }

    @PutMapping("/{zoneId}")
    @Operation(summary = "Actualizar zona", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Zona actualizada")
    @ApiResponse(responseCode = "404", description = "Zona no encontrada")
    public Mono<ZoneResponse> update(
            @PathVariable Long siteId,
            @PathVariable Long zoneId,
            @Valid @RequestBody ZoneRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return service.update(siteId, zoneId, request, username);
    }

    @DeleteMapping("/{zoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar zona (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Zona desactivada")
    @ApiResponse(responseCode = "404", description = "Zona no encontrada")
    public Mono<Void> delete(
            @PathVariable Long siteId,
            @PathVariable Long zoneId,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return service.delete(siteId, zoneId, username);
    }
}
