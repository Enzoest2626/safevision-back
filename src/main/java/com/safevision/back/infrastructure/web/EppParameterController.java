package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.EppParameterService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.EppParameterRequest;
import com.safevision.back.infrastructure.web.dto.EppParameterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/parameters")
@Tag(name = "EPP Parameters", description = "Reglas EPP requeridas por obra")
public class EppParameterController {

    private final EppParameterService service;

    public EppParameterController(EppParameterService service) {
        this.service = service;
    }

    @GetMapping("/{siteId}")
    @Operation(summary = "Consultar EPPs requeridos para una obra",
               description = "Si la obra no tiene configuración, retorna todos los EPPs activos del catálogo.")
    @ApiResponse(responseCode = "200", description = "EPPs requeridos para la obra")
    public Mono<ApiEnvelope<EppParameterResponse>> findBySite(@PathVariable Long siteId) {
        return ApiEnvelope.wrap(service.findBySite(siteId), HttpStatus.OK);
    }

    @PutMapping("/{siteId}")
    @Operation(summary = "Actualizar EPPs requeridos para una obra",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Configuración actualizada")
    @ApiResponse(responseCode = "400", description = "Código EPP inválido o lista vacía")
    public Mono<ApiEnvelope<EppParameterResponse>> updateForSite(
            @PathVariable Long siteId,
            @Valid @RequestBody EppParameterRequest request,
            @RequestHeader(value = "X-Username", defaultValue = "system") String username) {
        return ApiEnvelope.wrap(service.updateForSite(siteId, request, username), HttpStatus.OK);
    }
}
