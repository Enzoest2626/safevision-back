package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.SiteService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import com.safevision.back.infrastructure.web.dto.SiteResponse;
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
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/sites")
@Tag(name = "Sites", description = "Gestión de obras de construcción")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping
    @Operation(summary = "Listar obras activas")
    @ApiResponse(responseCode = "200", description = "Lista de obras")
    public Mono<ApiEnvelope<List<SiteResponse>>> findAll() {
        return ApiEnvelope.wrapList(siteService.findAll(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de obra")
    @ApiResponse(responseCode = "200", description = "Obra encontrada")
    public Mono<ApiEnvelope<SiteResponse>> findById(@PathVariable Long id) {
        return ApiEnvelope.wrap(siteService.findById(id), HttpStatus.OK);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear obra", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Obra creada")
    public Mono<ApiEnvelope<SiteResponse>> create(@Valid @RequestBody SiteRequest request,
                                     @RequestAttribute("username") String username) {
        return ApiEnvelope.wrap(siteService.create(request, username), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar obra", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Obra actualizada")
    public Mono<ApiEnvelope<SiteResponse>> update(@PathVariable Long id,
                                     @Valid @RequestBody SiteRequest request,
                                     @RequestAttribute("username") String username) {
        return ApiEnvelope.wrap(siteService.update(id, request, username), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar obra (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Obra desactivada")
    public Mono<ApiEnvelope<Void>> delete(@PathVariable Long id,
                             @RequestAttribute("username") String username) {
        return ApiEnvelope.wrapVoid(siteService.delete(id, username), HttpStatus.OK);
    }
}
