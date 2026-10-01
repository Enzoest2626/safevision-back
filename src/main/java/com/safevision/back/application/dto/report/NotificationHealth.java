package com.safevision.back.application.dto.report;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Salud del canal de alertas (Telegram) en el periodo: cuántas se intentaron,
 * cuántas fallaron, cuándo fue el último fallo y qué obras tienen al menos
 * un fallo (para accionar: revisar vínculo del supervisor).
 */
public record NotificationHealth(long total, long failed, LocalDateTime lastFailedAt, List<String> failingSites) {}
