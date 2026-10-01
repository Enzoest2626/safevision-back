package com.safevision.back.application.dto.report;

/** Celda del heatmap zona×hora: solo se emiten celdas no-cero. */
public record ZoneHourCount(String zone, int hour, long total) {}
