package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.SiteContactService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
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
    public Mono<ApiEnvelope<List<SiteContactResponse>>> findBySite(@PathVariable Long siteId) {
        return ApiEnvelope.wrapList(service.findBySite(siteId), HttpStatus.OK);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Agregar contacto a la obra",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Contacto agregado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    public Mono<ApiEnvelope<SiteContactResponse>> create(
            @PathVariable Long siteId,
            @Valid @RequestBody SiteContactRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrap(service.create(siteId, request, username), HttpStatus.CREATED);
    }

    @PutMapping("/{contactId}")
    @Operation(summary = "Actualizar contacto",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Contacto actualizado")
    @ApiResponse(responseCode = "404", description = "Contacto no encontrado")
    public Mono<ApiEnvelope<SiteContactResponse>> update(
            @PathVariable Long siteId,
            @PathVariable Long contactId,
            @Valid @RequestBody SiteContactRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrap(service.update(siteId, contactId, request, username), HttpStatus.OK);
    }

    @DeleteMapping("/{contactId}")
    @Operation(summary = "Desactivar contacto (soft delete)",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Contacto desactivado")
    @ApiResponse(responseCode = "404", description = "Contacto no encontrado")
    public Mono<ApiEnvelope<Void>> delete(
            @PathVariable Long siteId,
            @PathVariable Long contactId,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrapVoid(service.delete(siteId, contactId, username), HttpStatus.OK);
    }
}
