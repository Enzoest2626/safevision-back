package com.safevision.back.controller;

import com.safevision.back.dto.SiteRequest;
import com.safevision.back.dto.SiteResponse;
import com.safevision.back.service.SiteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/sites")
@Tag(name = "Sites", description = "Gestión de obras de construcción")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping
    @Operation(summary = "Listar obras activas")
    @ApiResponse(responseCode = "200", description = "Lista de obras")
    public Flux<SiteResponse> findAll() {
        return siteService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de obra")
    @ApiResponse(responseCode = "200", description = "Obra encontrada")
    @ApiResponse(responseCode = "404", description = "Obra no encontrada")
    public Mono<SiteResponse> findById(@PathVariable Long id) {
        return siteService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear obra", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Obra creada")
    public Mono<SiteResponse> create(@Valid @RequestBody SiteRequest request,
                                     @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return siteService.create(request, username);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar obra", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Obra actualizada")
    @ApiResponse(responseCode = "404", description = "Obra no encontrada")
    public Mono<SiteResponse> update(@PathVariable Long id,
                                     @Valid @RequestBody SiteRequest request,
                                     @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return siteService.update(id, request, username);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar obra (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Obra desactivada")
    @ApiResponse(responseCode = "404", description = "Obra no encontrada")
    public Mono<Void> delete(@PathVariable Long id,
                             @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return siteService.delete(id, username);
    }
}
