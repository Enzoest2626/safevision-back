package com.safevision.back.application.dto.report;

/** Conteo de incidentes por día de la semana (1=Monday .. 7=Sunday, ISO). */
public record WeekdayCount(int weekday, long total) {}
