package com.safevision.back.infrastructure.persistence;

import com.safevision.back.application.dto.report.ReportShift;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Fragmento AND compartido para filtrar agregados por turno sobre una
 * columna TIMESTAMP (siempre {@code incidents.occurred_at}, con o sin alias).
 * Sin turno (null) el fragmento es tautología — los mismos SQL sirven para
 * ambos casos sin duplicar consultas.
 */
final class ReportShiftSql {

    private ReportShiftSql() {
    }

    static String clause(String column) {
        return "AND (CAST(:fromHour AS INT) IS NULL"
                + " OR EXTRACT(HOUR FROM " + column + ") BETWEEN :fromHour AND :toHour)";
    }

    static DatabaseClient.GenericExecuteSpec bind(
            DatabaseClient.GenericExecuteSpec spec, ReportShift shift) {
        if (shift == null) {
            return spec.bindNull("fromHour", Integer.class).bindNull("toHour", Integer.class);
        }
        return spec.bind("fromHour", shift.fromHour()).bind("toHour", shift.toHour());
    }

    static DatabaseClient.GenericExecuteSpec bindSiteId(
            DatabaseClient.GenericExecuteSpec spec, Long siteId) {
        return siteId != null ? spec.bind("siteId", siteId) : spec.bindNull("siteId", Long.class);
    }
}
