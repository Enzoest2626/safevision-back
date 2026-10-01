package com.safevision.back.infrastructure.persistence;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import com.safevision.back.application.ports.out.ReportRepositoryPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Único adaptador de este repo que arma SQL de agregación a mano en vez de
 * usar un ReactiveCrudRepository — no hay una entidad "reporte" que
 * persistir, son proyecciones de solo lectura sobre incidents/sites/zones/
 * workers/notifications. Se usa DatabaseClient (no @Query de Spring Data)
 * para controlar explícitamente el mapeo de filas y el binding de siteId
 * opcional (bindNull cuando es "todas las obras") — evita depender de
 * proyección automática por convención de nombres.
 */
@Repository
public class ReportQueryRepository implements ReportRepositoryPort {

    private final DatabaseClient databaseClient;

    public ReportQueryRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    private DatabaseClient.GenericExecuteSpec bindSiteId(DatabaseClient.GenericExecuteSpec spec, Long siteId) {
        return siteId != null ? spec.bind("siteId", siteId) : spec.bindNull("siteId", Long.class);
    }

    @Override
    public Mono<Long> countIncidents(Long siteId, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT COUNT(*) AS total FROM incidents
                WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                  AND occurred_at BETWEEN :from AND :to
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to), siteId);
        return spec.map((row, meta) -> row.get("total", Long.class)).one();
    }

    @Override
    public Flux<CategoryCount> countByEppType(Long siteId, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT label, COUNT(*) AS total FROM (
                    SELECT unnest(missing_epp) AS label FROM incidents
                    WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                      AND occurred_at BETWEEN :from AND :to
                ) t
                GROUP BY label
                ORDER BY total DESC
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to), siteId);
        return spec.map((row, meta) -> new CategoryCount(row.get("label", String.class), row.get("total", Long.class))).all();
    }

    @Override
    public Flux<CategoryCount> countBySite(Long siteId, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT s.name AS label, COUNT(i.id) AS total
                FROM sites s
                LEFT JOIN incidents i ON i.site_id = s.id
                    AND i.occurred_at BETWEEN :from AND :to
                WHERE s.active = true
                  AND (:siteId::bigint IS NULL OR s.id = :siteId)
                GROUP BY s.id, s.name
                ORDER BY total DESC, s.name
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to), siteId);
        return spec.map((row, meta) -> new CategoryCount(row.get("label", String.class), row.get("total", Long.class))).all();
    }

    @Override
    public Flux<DailyCount> countByDay(Long siteId, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT date_trunc('day', occurred_at)::date AS day, COUNT(*) AS total
                FROM incidents
                WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                  AND occurred_at BETWEEN :from AND :to
                GROUP BY day
                ORDER BY day
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to), siteId);
        return spec.map((row, meta) -> new DailyCount(row.get("day", LocalDate.class), row.get("total", Long.class))).all();
    }

    @Override
    public Flux<ZoneCount> countByZone(Long siteId, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT z.name AS zone_name, s.name AS site_name, COUNT(i.id) AS total
                FROM zones z
                JOIN sites s ON s.id = z.site_id
                LEFT JOIN cameras c ON c.zone_id = z.id
                LEFT JOIN incidents i ON i.camera_id = c.id
                    AND i.occurred_at BETWEEN :from AND :to
                WHERE z.active = true
                  AND (:siteId::bigint IS NULL OR z.site_id = :siteId)
                GROUP BY z.id, z.name, s.name
                ORDER BY total DESC, z.name
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to), siteId);
        return spec.map((row, meta) -> new ZoneCount(
                row.get("zone_name", String.class), row.get("site_name", String.class), row.get("total", Long.class))).all();
    }

    @Override
    public Flux<WorkerCount> topWorkers(Long siteId, LocalDateTime from, LocalDateTime to, int limit) {
        String sql = """
                SELECT (w.first_name || ' ' || w.last_name) AS worker_name, COUNT(i.id) AS total
                FROM workers w
                JOIN incidents i ON i.worker_id = w.id
                    AND i.occurred_at BETWEEN :from AND :to
                WHERE (:siteId::bigint IS NULL OR w.site_id = :siteId)
                GROUP BY w.id, w.first_name, w.last_name
                ORDER BY total DESC, worker_name
                LIMIT :limit
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to).bind("limit", limit), siteId);
        return spec.map((row, meta) -> new WorkerCount(row.get("worker_name", String.class), row.get("total", Long.class))).all();
    }

    @Override
    public Mono<NotificationHealth> notificationHealth(Long siteId, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE ns.code = 'FAILED') AS failed
                FROM notifications n
                JOIN notification_statuses ns ON ns.id = n.status_id
                JOIN incidents i ON i.id = n.incident_id
                WHERE (:siteId::bigint IS NULL OR i.site_id = :siteId)
                  AND i.occurred_at BETWEEN :from AND :to
                """;
        DatabaseClient.GenericExecuteSpec spec = bindSiteId(
                databaseClient.sql(sql).bind("from", from).bind("to", to), siteId);
        return spec.map((row, meta) -> new NotificationHealth(row.get("total", Long.class), row.get("failed", Long.class))).one();
    }
}
