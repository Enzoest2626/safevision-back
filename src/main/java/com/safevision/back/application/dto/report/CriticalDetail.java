package com.safevision.back.application.dto.report;

/**
 * Explica la obra crítica del periodo: EPP dominante, zona dominante y
 * variación % contra el periodo anterior (null cuando el periodo anterior
 * no tiene base con qué comparar — mismo criterio honesto que el resumen).
 */
public record CriticalDetail(String site, long total, String topEpp, String topZone, Double variationPct) {}
