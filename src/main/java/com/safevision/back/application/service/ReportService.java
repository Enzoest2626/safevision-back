package com.safevision.back.application.service;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportResult;
import com.safevision.back.application.dto.report.ReportSummary;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import com.safevision.back.application.ports.out.ReportRepositoryPort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple6;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquesta HU12 (reportes): dispara las agregaciones de {@link ReportRepositoryPort}
 * en paralelo y arma el resultado. Todo lo que se ve acá sale de conteos reales
 * sobre incidents/sites/zones/workers/notifications — no hay número inventado.
 * "Cumplimiento %" queda deliberadamente fuera: el sistema solo registra
 * violaciones EPP, nunca chequeos conformes, así que no hay un denominador
 * real con el que calcular un porcentaje de cumplimiento honesto.
 */
@Service
public class ReportService {

    private static final int DEFAULT_PERIOD_DAYS = 30;
    private static final int TOP_WORKERS_LIMIT = 5;

    private final ReportRepositoryPort reportRepo;

    public ReportService(ReportRepositoryPort reportRepo) {
        this.reportRepo = reportRepo;
    }

    public Mono<ReportResult> buildReport(Long siteId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        LocalDateTime effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_PERIOD_DAYS);

        long periodDays = ChronoUnit.DAYS.between(effectiveFrom.toLocalDate(), effectiveTo.toLocalDate()) + 1;
        LocalDateTime previousTo = effectiveFrom;
        LocalDateTime previousFrom = effectiveFrom.minusDays(periodDays);

        Mono<Tuple6<Long, Long, List<CategoryCount>, List<CategoryCount>, List<DailyCount>, List<ZoneCount>>> group1 = Mono.zip(
                reportRepo.countIncidents(siteId, effectiveFrom, effectiveTo),
                reportRepo.countIncidents(siteId, previousFrom, previousTo),
                reportRepo.countByEppType(siteId, effectiveFrom, effectiveTo).collectList(),
                reportRepo.countBySite(siteId, effectiveFrom, effectiveTo).collectList(),
                reportRepo.countByDay(siteId, effectiveFrom, effectiveTo).collectList(),
                reportRepo.countByZone(siteId, effectiveFrom, effectiveTo).collectList());

        Mono<Tuple2<List<WorkerCount>, NotificationHealth>> group2 = Mono.zip(
                reportRepo.topWorkers(siteId, effectiveFrom, effectiveTo, TOP_WORKERS_LIMIT).collectList(),
                reportRepo.notificationHealth(siteId, effectiveFrom, effectiveTo));

        LocalDate rangeStart = effectiveFrom.toLocalDate();
        LocalDate rangeEnd = effectiveTo.toLocalDate();

        return group1.zipWith(group2).map(combined -> {
            Tuple6<Long, Long, List<CategoryCount>, List<CategoryCount>, List<DailyCount>, List<ZoneCount>> g1 = combined.getT1();
            Tuple2<List<WorkerCount>, NotificationHealth> g2 = combined.getT2();

            long total = g1.getT1();
            long previousTotal = g1.getT2();
            List<CategoryCount> byEppType = g1.getT3();
            List<CategoryCount> bySite = g1.getT4();
            List<DailyCount> dailyTrend = zeroFillDays(g1.getT5(), rangeStart, rangeEnd);
            List<ZoneCount> byZone = g1.getT6();
            List<WorkerCount> topWorkers = g2.getT1();
            NotificationHealth notifications = g2.getT2();

            ReportSummary summary = buildSummary(total, previousTotal, bySite);
            return new ReportResult(summary, byEppType, bySite, dailyTrend, byZone, topWorkers, notifications);
        });
    }

    private ReportSummary buildSummary(long total, long previousTotal, List<CategoryCount> bySite) {
        CategoryCount critical = bySite.stream().filter(c -> c.total() > 0).findFirst().orElse(null);

        Double trendPct;
        String trendDirection;
        if (previousTotal == 0 && total == 0) {
            trendPct = 0.0;
            trendDirection = "FLAT";
        } else if (previousTotal == 0) {
            // Sin base en el periodo anterior no hay un "%" honesto que mostrar,
            // pero la dirección (subió) sigue siendo verdad.
            trendPct = null;
            trendDirection = "UP";
        } else {
            double rawPct = Math.abs(total - previousTotal) * 100.0 / previousTotal;
            trendPct = Math.round(rawPct * 10) / 10.0;
            trendDirection = total > previousTotal ? "UP" : total < previousTotal ? "DOWN" : "FLAT";
        }

        return new ReportSummary(
                total,
                critical != null ? critical.label() : null,
                critical != null ? critical.total() : 0,
                trendPct,
                trendDirection);
    }

    private List<DailyCount> zeroFillDays(List<DailyCount> raw, LocalDate start, LocalDate end) {
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
}
