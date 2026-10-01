package com.safevision.back.infrastructure.persistence;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.HourlyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportShift;
import com.safevision.back.application.dto.report.WeekdayCount;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import com.safevision.back.application.dto.report.ZoneHourCount;
import com.safevision.back.application.ports.out.ReportRepositoryPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.bind;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.bindSiteId;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.clause;

/**
 * Agregados base de reportes con DatabaseClient (sin entidad "reporte" que persistir,
 * solo GROUP BY de lectura). El detalle del dashboard vive en {@link ReportDetailQueries}
 * y {@link ReportNotificationQueries} — acá solo se delega. En los LEFT JOIN el filtro
 * de turno va en el ON, no en el WHERE, para no borrar las filas de 0 incidentes.
 */
@Repository
public class ReportQueryRepository implements ReportRepositoryPort {

    private final DatabaseClient databaseClient;
    private final ReportDetailQueries detailQueries;
    private final ReportNotificationQueries notificationQueries;

    public ReportQueryRepository(DatabaseClient databaseClient, ReportDetailQueries detailQueries,
            ReportNotificationQueries notificationQueries) {
        this.databaseClient = databaseClient;
        this.detailQueries = detailQueries;
        this.notificationQueries = notificationQueries;
    }

    @Override
    public Mono<Long> countIncidents(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT COUNT(*) AS total FROM incidents
                WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                  AND occurred_at BETWEEN :from AND :to
                """ + clause("occurred_at");
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> row.get("total", Long.class)).one();
    }

    @Override
    public Flux<CategoryCount> countByEppType(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT label, COUNT(*) AS total FROM (
                    SELECT unnest(missing_epp) AS label FROM incidents
                    WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                      AND occurred_at BETWEEN :from AND :to
                """ + clause("occurred_at") + """
                 ) t GROUP BY label ORDER BY total DESC
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new CategoryCount(row.get("label", String.class),
                row.get("total", Long.class))).all();
    }

    @Override
    public Flux<CategoryCount> countBySite(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT s.name AS label, COUNT(i.id) AS total FROM sites s
                LEFT JOIN incidents i ON i.site_id = s.id AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at") + """
                 WHERE s.active = true AND (:siteId::bigint IS NULL OR s.id = :siteId)
                GROUP BY s.id, s.name ORDER BY total DESC, s.name
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new CategoryCount(row.get("label", String.class),
                row.get("total", Long.class))).all();
    }

    @Override
    public Flux<DailyCount> countByDay(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT date_trunc('day', occurred_at)::date AS day, COUNT(*) AS total FROM incidents
                WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                  AND occurred_at BETWEEN :from AND :to
                """ + clause("occurred_at") + """
                 GROUP BY day ORDER BY day
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new DailyCount(row.get("day", LocalDate.class),
                row.get("total", Long.class))).all();
    }

    @Override
    public Flux<ZoneCount> countByZone(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT z.name AS zone_name, s.name AS site_name, COUNT(i.id) AS total
                FROM zones z JOIN sites s ON s.id = z.site_id
                LEFT JOIN cameras c ON c.zone_id = z.id
                LEFT JOIN incidents i ON i.camera_id = c.id AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at") + """
                 WHERE z.active = true AND (:siteId::bigint IS NULL OR z.site_id = :siteId)
                GROUP BY z.id, z.name, s.name ORDER BY total DESC, z.name
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new ZoneCount(row.get("zone_name", String.class),
                row.get("site_name", String.class), row.get("total", Long.class))).all();
    }

    @Override
    public Flux<WorkerCount> topWorkers(Long siteId, LocalDateTime from, LocalDateTime to, int limit,
            ReportShift shift) {
        String sql = """
                SELECT (w.first_name || ' ' || w.last_name) AS worker_name, COUNT(i.id) AS total
                FROM workers w JOIN incidents i ON i.worker_id = w.id AND i.occurred_at BETWEEN :from AND :to
                WHERE (:siteId::bigint IS NULL OR w.site_id = :siteId)
                """ + clause("i.occurred_at") + """
                 GROUP BY w.id, w.first_name, w.last_name ORDER BY total DESC, worker_name LIMIT :limit
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(bindSiteId(databaseClient.sql(sql)
                .bind("from", from).bind("to", to).bind("limit", limit), siteId), shift);
        return spec.map((row, meta) -> new WorkerCount(row.get("worker_name", String.class),
                row.get("total", Long.class))).all();
    }

    @Override
    public Flux<HourlyCount> countByHour(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        return detailQueries.countByHour(siteId, from, to, shift);
    }

    @Override
    public Flux<WeekdayCount> countByWeekday(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        return detailQueries.countByWeekday(siteId, from, to, shift);
    }

    @Override
    public Flux<ZoneHourCount> countByZoneHour(Long siteId, LocalDateTime from, LocalDateTime to,
            ReportShift shift) {
        return detailQueries.countByZoneHour(siteId, from, to, shift);
    }

    @Override
    public Mono<String> topEppForSite(String siteName, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        return detailQueries.topEppForSite(siteName, from, to, shift);
    }

    @Override
    public Mono<String> topZoneForSite(String siteName, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        return detailQueries.topZoneForSite(siteName, from, to, shift);
    }

    @Override
    public Mono<NotificationHealth> notificationHealth(Long siteId, LocalDateTime from, LocalDateTime to,
            ReportShift shift) {
        return notificationQueries.notificationHealth(siteId, from, to, shift);
    }
}
