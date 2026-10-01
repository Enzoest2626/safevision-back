package com.safevision.back.application.dto.report;

/** Salud del canal de alertas (Telegram) en el periodo: cuántas se intentaron vs. cuántas fallaron. */
public record NotificationHealth(long total, long failed) {}
