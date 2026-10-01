package com.safevision.back.application.dto.report;

/** Conteo de incidentes agrupado por una categoría genérica (tipo de EPP, obra). */
public record CategoryCount(String label, long total) {}
