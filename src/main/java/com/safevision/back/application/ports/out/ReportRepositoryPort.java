package com.safevision.back.application.ports.out;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.HourlyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportShift;
import com.safevision.back.application.dto.report.WeekdayCount;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import com.safevision.back.application.dto.report.ZoneHourCount;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Consultas de agregación para reportes. No es un repositorio de
 * Spring Data (no hay una entidad "reporte" que persistir) — es un puerto de
 * solo lectura implementado con DatabaseClient sobre incidents/sites/zones/
 * workers/notifications. siteId=null en cualquier método significa "todas
 * las obras"; shift=null significa "sin filtro de turno".
 */
public interface ReportRepositoryPort {

    Mono<Long> countIncidents(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Un incidente con missing_epp=[casco,chaleco] suma 1 a cada tipo — no 1 al total. */
    Flux<CategoryCount> countByEppType(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Todas las obras activas, incluidas las que tuvieron 0 incidentes en el periodo. */
    Flux<CategoryCount> countBySite(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    Flux<DailyCount> countByDay(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Todas las zonas activas (de la obra filtrada, o de todas), incluidas las de 0 incidentes. */
    Flux<ZoneCount> countByZone(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Solo trabajadores con al menos un incidente — es un ranking, no un listado completo. */
    Flux<WorkerCount> topWorkers(Long siteId, LocalDateTime from, LocalDateTime to, int limit, ReportShift shift);

    /** Horas con incidentes en el periodo (el servicio rellena con 0 las 24). */
    Flux<HourlyCount> countByHour(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Días de semana con incidentes, 1=Monday (el servicio rellena 1-7). */
    Flux<WeekdayCount> countByWeekday(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Heatmap zona×hora: solo celdas no-cero. */
    Flux<ZoneHourCount> countByZoneHour(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** EPP dominante de una obra en el periodo (vacío si no tuvo incidentes). */
    Mono<String> topEppForSite(String siteName, LocalDateTime from, LocalDateTime to, ReportShift shift);

    /** Zona dominante de una obra en el periodo (vacío si no tuvo incidentes). */
    Mono<String> topZoneForSite(String siteName, LocalDateTime from, LocalDateTime to, ReportShift shift);

    Mono<NotificationHealth> notificationHealth(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift);
}
