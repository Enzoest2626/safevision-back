package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.IncidentService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.EvidenceResponse;
import com.safevision.back.infrastructure.web.dto.IncidentRequest;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents", description = "Registro de incidentes EPP (HU10)")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar incidente EPP",
               description = "Invocado exclusivamente por el módulo de Computer Vision. " +
                       "Persiste el incidente y su evidencia, y notifica a la obra vía Telegram.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Incidente registrado")
    @ApiResponse(responseCode = "400", description = "Worker, cámara u obra desconocidos")
    public Mono<ApiEnvelope<IncidentResponse>> register(
            @Valid @RequestBody IncidentRequest request,
            @Parameter(description = "Id de correlación generado por el CV para seguir el evento en los logs "
                    + "(no se persiste ni se envía a Telegram); si no llega, se autogenera uno.")
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String effectiveTraceId = (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
        return ApiEnvelope.wrap(incidentService.register(request, effectiveTraceId), HttpStatus.CREATED);
    }

    @GetMapping
    @Operation(summary = "Listar incidentes", description = "Filtros opcionales por obra, trabajador y rango de fechas.")
    @ApiResponse(responseCode = "200", description = "Lista de incidentes")
    public Mono<ApiEnvelope<List<IncidentResponse>>> findAll(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiEnvelope.wrapList(incidentService.findByFilter(siteId, workerId, from, to), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de incidente")
    @ApiResponse(responseCode = "200", description = "Incidente encontrado")
    @ApiResponse(responseCode = "404", description = "Incidente no encontrado")
    public Mono<ApiEnvelope<IncidentResponse>> findById(@PathVariable Long id) {
        return ApiEnvelope.wrap(incidentService.findById(id), HttpStatus.OK);
    }

    @GetMapping("/{id}/evidence")
    @Operation(summary = "Evidencia del incidente (foto/clip)",
               description = "Cada fila trae una URL de S3 prefirmada de corta duración — no hay link "
                       + "público permanente. Filas legacy con evidencia inline (frame_b64) traen url=null.")
    @ApiResponse(responseCode = "200", description = "Lista de evidencia (puede estar vacía)")
    @ApiResponse(responseCode = "404", description = "Incidente no encontrado")
    public Mono<ApiEnvelope<List<EvidenceResponse>>> findEvidence(@PathVariable Long id) {
        return ApiEnvelope.wrapList(incidentService.findEvidenceByIncidentId(id), HttpStatus.OK);
    }
}
