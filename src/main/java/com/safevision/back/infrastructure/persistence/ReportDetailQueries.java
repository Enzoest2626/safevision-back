package com.safevision.back.infrastructure.persistence;

import com.safevision.back.application.dto.report.HourlyCount;
import com.safevision.back.application.dto.report.ReportShift;
import com.safevision.back.application.dto.report.WeekdayCount;
import com.safevision.back.application.dto.report.ZoneHourCount;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static com.safevision.back.infrastructure.persistence.ReportShiftSql.bind;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.bindSiteId;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.clause;

/**
 * Agregados de detalle para el dashboard (hora, día de semana, heatmap
 * zona×hora, dominantes de la obra crítica). Se separó de
 * {@link ReportQueryRepository} por el límite de 150 líneas por clase —
 * mismo estilo SQL (DatabaseClient + siteId opcional + filtro de turno).
 */
@Repository
public class ReportDetailQueries {

    private final DatabaseClient databaseClient;

    public ReportDetailQueries(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<HourlyCount> countByHour(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT EXTRACT(HOUR FROM occurred_at)::int AS hour, COUNT(*) AS total
                FROM incidents
                WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                  AND occurred_at BETWEEN :from AND :to
                """ + clause("occurred_at") + """
                 GROUP BY hour
                ORDER BY hour
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new HourlyCount(
                row.get("hour", Integer.class), row.get("total", Long.class))).all();
    }

    public Flux<WeekdayCount> countByWeekday(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT EXTRACT(ISODOW FROM occurred_at)::int AS weekday, COUNT(*) AS total
                FROM incidents
                WHERE (:siteId::bigint IS NULL OR site_id = :siteId)
                  AND occurred_at BETWEEN :from AND :to
                """ + clause("occurred_at") + """
                 GROUP BY weekday
                ORDER BY weekday
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new WeekdayCount(
                row.get("weekday", Integer.class), row.get("total", Long.class))).all();
    }

    public Flux<ZoneHourCount> countByZoneHour(
            Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT z.name AS zone_name, EXTRACT(HOUR FROM i.occurred_at)::int AS hour, COUNT(*) AS total
                FROM zones z
                JOIN cameras c ON c.zone_id = z.id
                JOIN incidents i ON i.camera_id = c.id
                WHERE z.active = true
                  AND (:siteId::bigint IS NULL OR z.site_id = :siteId)
                  AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at") + """
                 GROUP BY z.id, z.name, hour
                ORDER BY z.name, hour
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new ZoneHourCount(row.get("zone_name", String.class),
                row.get("hour", Integer.class), row.get("total", Long.class))).all();
    }

    public Mono<String> topEppForSite(
            String siteName, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT unnest(i.missing_epp) AS epp, COUNT(*) AS total
                FROM incidents i
                JOIN sites s ON s.id = i.site_id
                WHERE s.name = :siteName
                  AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at") + """
                 GROUP BY epp
                ORDER BY total DESC, epp
                LIMIT 1
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                databaseClient.sql(sql).bind("siteName", siteName).bind("from", from).bind("to", to), shift);
        return spec.map((row, meta) -> row.get("epp", String.class)).one();
    }

    public Mono<String> topZoneForSite(
            String siteName, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT z.name AS zone_name, COUNT(i.id) AS total
                FROM zones z
                JOIN cameras c ON c.zone_id = z.id
                JOIN incidents i ON i.camera_id = c.id
                JOIN sites s ON s.id = z.site_id
                WHERE s.name = :siteName
                  AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at") + """
                 GROUP BY z.id, z.name
                ORDER BY total DESC, z.name
                LIMIT 1
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                databaseClient.sql(sql).bind("siteName", siteName).bind("from", from).bind("to", to), shift);
        return spec.map((row, meta) -> row.get("zone_name", String.class)).one();
    }
}
