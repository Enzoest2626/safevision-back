package com.safevision.back.infrastructure.web;

import com.safevision.back.application.service.IncidentQueryService;
import com.safevision.back.application.service.IncidentService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.web.dto.CvIncidentMessage;
import com.safevision.back.infrastructure.web.dto.EvidenceResponse;
import com.safevision.back.infrastructure.web.dto.IncidentRequest;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import com.safevision.back.infrastructure.web.dto.PagedResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Incidents", description = "Registro e ingesta de incidentes EPP")
public class IncidentController {

    private final IncidentService incidentService;
    private final IncidentQueryService incidentQueryService;

    public IncidentController(IncidentService incidentService, IncidentQueryService incidentQueryService) {
        this.incidentService = incidentService;
        this.incidentQueryService = incidentQueryService;
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar incidente EPP (legacy, evidencia inline en base64)",
               description = "Invocado por el módulo de Computer Vision. Persiste el incidente y notifica "
                       + "a la obra vía Telegram.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Incidente registrado")
    public Mono<ApiEnvelope<IncidentResponse>> register(
            @Valid @RequestBody IncidentRequest request,
            @Parameter(description = "Id de correlación generado por el CV para seguir el evento en los logs "
                    + "(no se persiste ni se envía a Telegram); si no llega, se autogenera uno.")
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String effectiveTraceId = (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
        return ApiEnvelope.wrap(incidentService.register(request, effectiveTraceId), HttpStatus.CREATED);
    }

    @PostMapping("/cv/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar incidente EPP (activo, con evidencia en S3)",
               description = "Invocado exclusivamente por el módulo de Computer Vision — la foto ya está "
                       + "subida a S3, este payload solo trae la referencia (photo_s3_key).",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Incidente registrado")
    public Mono<ApiEnvelope<IncidentResponse>> registerFromCv(
            @Valid @RequestBody CvIncidentMessage message,
            @Parameter(description = "Id de correlación generado por el CV para seguir el evento en los logs; "
                    + "si no llega, se autogenera uno.")
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String effectiveTraceId = (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
        return ApiEnvelope.wrap(incidentService.registerFromCv(message, effectiveTraceId), HttpStatus.CREATED);
    }

    @PostMapping("/cv/incidents/clips")
    @Operation(summary = "Registrar clip de video de un incidente ya notificado",
               description = "Llega minutos después del incidente, correlacionado por incident_id.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Clip registrado")
    public Mono<ApiEnvelope<Void>> registerClip(@Valid @RequestBody CvClipReadyMessage message) {
        return ApiEnvelope.wrapVoid(incidentService.registerClipReady(message), HttpStatus.OK);
    }

    @GetMapping("/incidents")
    @Operation(summary = "Listar incidentes (paginado)",
               description = "Filtros opcionales por obra, trabajador y rango de fecha+hora. "
                       + "page arranca en 1 (default 1); size default 20, máximo 50.")
    @ApiResponse(responseCode = "200", description = "Página de incidentes")
    public Mono<ApiEnvelope<PagedResponse<IncidentResponse>>> findAll(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiEnvelope.wrap(
                incidentQueryService.findByFilter(siteId, workerId, from, to, page, size), HttpStatus.OK);
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "Detalle de incidente")
    @ApiResponse(responseCode = "200", description = "Incidente encontrado")
    public Mono<ApiEnvelope<IncidentResponse>> findById(@PathVariable Long id) {
        return ApiEnvelope.wrap(incidentQueryService.findById(id), HttpStatus.OK);
    }

    @GetMapping("/incidents/{id}/evidence")
    @Operation(summary = "Evidencia del incidente (foto/clip)",
               description = "Cada fila trae una URL de S3 prefirmada de corta duración — no hay link "
                       + "público permanente. Filas legacy con evidencia inline (frame_b64) traen url=null.")
    @ApiResponse(responseCode = "200", description = "Lista de evidencia (puede estar vacía)")
    public Mono<ApiEnvelope<List<EvidenceResponse>>> findEvidence(@PathVariable Long id) {
        return ApiEnvelope.wrapList(incidentQueryService.findEvidenceByIncidentId(id), HttpStatus.OK);
    }
}
