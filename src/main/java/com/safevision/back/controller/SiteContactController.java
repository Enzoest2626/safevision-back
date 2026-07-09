package com.safevision.back.controller;

import com.safevision.back.dto.SiteContactRequest;
import com.safevision.back.dto.SiteContactResponse;
import com.safevision.back.service.SiteContactService;
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
@RequestMapping("/api/v1/sites/{siteId}/contacts")
@Tag(name = "Site Contacts", description = "Contactos por obra — reciben alertas Telegram al detectar incidentes")
public class SiteContactController {

    private final SiteContactService service;

    public SiteContactController(SiteContactService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Listar contactos activos de una obra")
    @ApiResponse(responseCode = "200", description = "Lista de contactos")
    public Flux<SiteContactResponse> findBySite(@PathVariable Long siteId) {
        return service.findBySite(siteId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Agregar contacto a la obra",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Contacto agregado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    public Mono<SiteContactResponse> create(
            @PathVariable Long siteId,
            @Valid @RequestBody SiteContactRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return service.create(siteId, request, username);
    }

    @PutMapping("/{contactId}")
    @Operation(summary = "Actualizar contacto",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Contacto actualizado")
    @ApiResponse(responseCode = "404", description = "Contacto no encontrado")
    public Mono<SiteContactResponse> update(
            @PathVariable Long siteId,
            @PathVariable Long contactId,
            @Valid @RequestBody SiteContactRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return service.update(siteId, contactId, request, username);
    }

    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar contacto (soft delete)",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Contacto desactivado")
    @ApiResponse(responseCode = "404", description = "Contacto no encontrado")
    public Mono<Void> delete(
            @PathVariable Long siteId,
            @PathVariable Long contactId,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return service.delete(siteId, contactId, username);
    }
}
