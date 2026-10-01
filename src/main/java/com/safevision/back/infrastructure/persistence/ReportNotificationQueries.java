package com.safevision.back.infrastructure.persistence;

import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportShift;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.safevision.back.infrastructure.persistence.ReportShiftSql.bind;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.bindSiteId;
import static com.safevision.back.infrastructure.persistence.ReportShiftSql.clause;

/**
 * Salud accionable del canal Telegram: intentadas vs. fallidas, último fallo
 * y obras con fallos (para revisar el vínculo del supervisor). Se separó de
 * {@link ReportQueryRepository} por el límite de 150 líneas por clase.
 */
@Repository
public class ReportNotificationQueries {

    private final DatabaseClient databaseClient;

    public ReportNotificationQueries(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<NotificationHealth> notificationHealth(
            Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        return Mono.zip(counts(siteId, from, to, shift), lastFailedAt(siteId, from, to, shift),
                        failingSites(siteId, from, to, shift).collectList())
                .map(t -> new NotificationHealth(t.getT1().total(), t.getT1().failed(),
                        t.getT2().orElse(null), t.getT3()));
    }

    private record Counts(long total, long failed) {}

    private Mono<Counts> counts(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE ns.code = 'FAILED') AS failed
                FROM notifications n
                JOIN notification_statuses ns ON ns.id = n.status_id
                JOIN incidents i ON i.id = n.incident_id
                WHERE (:siteId::bigint IS NULL OR i.site_id = :siteId)
                  AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at");
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> new Counts(
                row.get("total", Long.class), row.get("failed", Long.class))).one();
    }

    private Mono<Optional<LocalDateTime>> lastFailedAt(
            Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT MAX(n.created_at) AS last_failed
                FROM notifications n
                JOIN notification_statuses ns ON ns.id = n.status_id
                JOIN incidents i ON i.id = n.incident_id
                WHERE ns.code = 'FAILED'
                  AND (:siteId::bigint IS NULL OR i.site_id = :siteId)
                  AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at");
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> Optional.ofNullable(row.get("last_failed", LocalDateTime.class))).one();
    }

    private Flux<String> failingSites(
            Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift) {
        String sql = """
                SELECT DISTINCT s.name AS site_name
                FROM notifications n
                JOIN notification_statuses ns ON ns.id = n.status_id
                JOIN incidents i ON i.id = n.incident_id
                JOIN sites s ON s.id = i.site_id
                WHERE ns.code = 'FAILED'
                  AND (:siteId::bigint IS NULL OR i.site_id = :siteId)
                  AND i.occurred_at BETWEEN :from AND :to
                """ + clause("i.occurred_at") + """
                 ORDER BY site_name
                """;
        DatabaseClient.GenericExecuteSpec spec = bind(
                bindSiteId(databaseClient.sql(sql).bind("from", from).bind("to", to), siteId), shift);
        return spec.map((row, meta) -> row.get("site_name", String.class)).all();
    }
}
