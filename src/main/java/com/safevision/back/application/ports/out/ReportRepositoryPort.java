package com.safevision.back.application.ports.out;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Consultas de agregación para reportes. No es un repositorio de
 * Spring Data (no hay una entidad "reporte" que persistir) — es un puerto de
 * solo lectura implementado con DatabaseClient sobre incidents/sites/zones/
 * workers/notifications. siteId=null en cualquier método significa "todas
 * las obras".
 */
public interface ReportRepositoryPort {

    Mono<Long> countIncidents(Long siteId, LocalDateTime from, LocalDateTime to);

    /** Un incidente con missing_epp=[casco,chaleco] suma 1 a cada tipo — no 1 al total. */
    Flux<CategoryCount> countByEppType(Long siteId, LocalDateTime from, LocalDateTime to);

    /** Todas las obras activas, incluidas las que tuvieron 0 incidentes en el periodo. */
    Flux<CategoryCount> countBySite(Long siteId, LocalDateTime from, LocalDateTime to);

    Flux<DailyCount> countByDay(Long siteId, LocalDateTime from, LocalDateTime to);

    /** Todas las zonas activas (de la obra filtrada, o de todas), incluidas las de 0 incidentes. */
    Flux<ZoneCount> countByZone(Long siteId, LocalDateTime from, LocalDateTime to);

    /** Solo trabajadores con al menos un incidente — es un ranking, no un listado completo. */
    Flux<WorkerCount> topWorkers(Long siteId, LocalDateTime from, LocalDateTime to, int limit);

    Mono<NotificationHealth> notificationHealth(Long siteId, LocalDateTime from, LocalDateTime to);
}
