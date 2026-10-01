package com.safevision.back.application.service;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.HourlyCount;
import com.safevision.back.application.dto.report.ReportSummary;
import com.safevision.back.application.dto.report.WeekdayCount;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Funciones puras de composición de reportes (rellenos con cero, resumen y
 * variación %). Se extrajeron de {@link ReportService} por el límite de 150
 * líneas por clase — acá no hay I/O, solo transformación de listas.
 */
final class ReportSupport {

    private ReportSupport() {
    }

    static List<DailyCount> zeroFillDays(List<DailyCount> raw, LocalDate start, LocalDate end) {
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (DailyCount dc : raw) {
            byDay.put(dc.day(), dc.total());
        }
        List<DailyCount> filled = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            filled.add(new DailyCount(day, byDay.getOrDefault(day, 0L)));
        }
        return filled;
    }

    /** Las 24 horas siempre presentes (0-23), aunque no haya incidentes. */
    static List<HourlyCount> zeroFillHours(List<HourlyCount> raw) {
        Map<Integer, Long> byHour = new HashMap<>();
        for (HourlyCount hc : raw) {
            byHour.put(hc.hour(), hc.total());
        }
        List<HourlyCount> filled = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            filled.add(new HourlyCount(hour, byHour.getOrDefault(hour, 0L)));
        }
        return filled;
    }

    /** Los 7 días siempre presentes (1=Monday). */
    static List<WeekdayCount> zeroFillWeekdays(List<WeekdayCount> raw) {
        Map<Integer, Long> byDay = new HashMap<>();
        for (WeekdayCount wc : raw) {
            byDay.put(wc.weekday(), wc.total());
        }
        List<WeekdayCount> filled = new ArrayList<>(7);
        for (int weekday = 1; weekday <= 7; weekday++) {
            filled.add(new WeekdayCount(weekday, byDay.getOrDefault(weekday, 0L)));
        }
        return filled;
    }

    static ReportSummary buildSummary(long total, long previousTotal, List<CategoryCount> bySite) {
        CategoryCount critical = bySite.stream().filter(c -> c.total() > 0).findFirst().orElse(null);
        Double trendPct = variationPct(total, previousTotal);
        String trendDirection;
        if (previousTotal == 0 && total == 0) {
            trendDirection = "FLAT";
        } else if (previousTotal == 0 || total > previousTotal) {
            trendDirection = "UP";
        } else {
            trendDirection = total < previousTotal ? "DOWN" : "FLAT";
        }
        return new ReportSummary(total, critical != null ? critical.label() : null,
                critical != null ? critical.total() : 0, trendPct, trendDirection);
    }

    /**
     * Variación % honesta contra el periodo anterior: null cuando no hay base
     * (no se inventa un porcentaje desde cero), 0.0 cuando ambos son cero.
     */
    static Double variationPct(long total, long previousTotal) {
        if (previousTotal == 0 && total == 0) {
            return 0.0;
        }
        if (previousTotal == 0) {
            return null;
        }
        return Math.round(Math.abs(total - previousTotal) * 1000.0 / previousTotal) / 10.0;
    }
}
