package com.safevision.back.infrastructure.web;

import com.safevision.back.application.dto.report.ReportResult;
import com.safevision.back.application.service.ReportService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/reports")
@Tag(name = "Reports", description = "Reportes agregados de incidentes EPP")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @Operation(summary = "Reporte agregado de incidentes",
               description = "Filtros opcionales por obra, rango de fechas (default: últimos 30 días) y turno "
                       + "(`shift=morning|afternoon`; mañana 06:00–11:59, tarde 12:00–20:59 — ver docs/ALCANCE.md, "
                       + "sin turno noche). Solo agrega conteos reales de incidents/sites/zones/workers/"
                       + "notifications — no incluye 'cumplimiento %' porque el sistema no registra chequeos "
                       + "conformes, solo violaciones. Sin identificación de personas (ver docs/ALCANCE.md): "
                       + "los agregados son por obra, zona, hora y turno.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Reporte generado")
    @ApiResponse(responseCode = "400", description = "shift inválido (solo morning|afternoon)")
    public Mono<ApiEnvelope<ReportResult>> get(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String shift) {
        return ApiEnvelope.wrap(reportService.buildReport(siteId, from, to, shift), HttpStatus.OK);
    }
}
