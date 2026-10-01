package com.safevision.back.application.dto.report;

/** Conteo de incidentes en una hora del día (0-23, hora del servidor; en prod Lima). */
public record HourlyCount(int hour, long total) {}
