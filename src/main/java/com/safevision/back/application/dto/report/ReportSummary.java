package com.safevision.back.application.dto.report;

/**
 * KPIs generales del periodo. trendPct/trendDirection son {@code null} cuando
 * el periodo anterior no tiene incidentes con qué comparar (no se puede
 * expresar un cambio porcentual honesto desde una base de cero).
 */
public record ReportSummary(
        long totalIncidents,
        String criticalSiteName,
        long criticalSiteTotal,
        Double trendPct,
        String trendDirection
) {}
