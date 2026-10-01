package com.safevision.back.application.service;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.CriticalDetail;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.HourlyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportResult;
import com.safevision.back.application.dto.report.ReportShift;
import com.safevision.back.application.dto.report.WeekdayCount;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import com.safevision.back.application.dto.report.ZoneHourCount;
import com.safevision.back.application.ports.out.ReportRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.safevision.back.application.service.ReportSupport.buildSummary;
import static com.safevision.back.application.service.ReportSupport.variationPct;
import static com.safevision.back.application.service.ReportSupport.zeroFillDays;
import static com.safevision.back.application.service.ReportSupport.zeroFillHours;
import static com.safevision.back.application.service.ReportSupport.zeroFillWeekdays;

/**
 * Orquesta los reportes: dispara las agregaciones de {@link ReportRepositoryPort}
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

    public Mono<ReportResult> buildReport(Long siteId, LocalDateTime from, LocalDateTime to, String shiftRaw) {
        ReportShift shift;
        try {
            shift = ReportShift.parse(shiftRaw);
        } catch (ResponseStatusException ex) {
            return Mono.error(ex);
        }
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        LocalDateTime effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_PERIOD_DAYS);

        long periodDays = ChronoUnit.DAYS.between(effectiveFrom.toLocalDate(), effectiveTo.toLocalDate()) + 1;
        LocalDateTime previousTo = effectiveFrom;
        LocalDateTime previousFrom = effectiveFrom.minusDays(periodDays);
        LocalDate rangeStart = effectiveFrom.toLocalDate();
        LocalDate rangeEnd = effectiveTo.toLocalDate();

        Mono<MainAggregates> main = Mono.zip(
                reportRepo.countIncidents(siteId, effectiveFrom, effectiveTo, shift),
                reportRepo.countIncidents(siteId, previousFrom, previousTo, shift),
                reportRepo.countByEppType(siteId, effectiveFrom, effectiveTo, shift).collectList(),
                reportRepo.countBySite(siteId, effectiveFrom, effectiveTo, shift).collectList(),
                reportRepo.countBySite(siteId, previousFrom, previousTo, shift).collectList(),
                reportRepo.countByDay(siteId, effectiveFrom, effectiveTo, shift).collectList(),
                reportRepo.countByZone(siteId, effectiveFrom, effectiveTo, shift).collectList(),
                reportRepo.countByHour(siteId, effectiveFrom, effectiveTo, shift).collectList())
                .map(t -> new MainAggregates(t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(),
                        zeroFillDays(t.getT6(), rangeStart, rangeEnd), t.getT7(), zeroFillHours(t.getT8())));

        Mono<ExtraAggregates> extras = Mono.zip(
                reportRepo.topWorkers(siteId, effectiveFrom, effectiveTo, TOP_WORKERS_LIMIT, shift).collectList(),
                reportRepo.notificationHealth(siteId, effectiveFrom, effectiveTo, shift),
                reportRepo.countByWeekday(siteId, effectiveFrom, effectiveTo, shift).collectList(),
                reportRepo.countByZoneHour(siteId, effectiveFrom, effectiveTo, shift).collectList())
                .map(t -> new ExtraAggregates(t.getT1(), t.getT2(), zeroFillWeekdays(t.getT3()), t.getT4()));

        return main.zipWith(extras).flatMap(p -> attachCritical(
                siteId, effectiveFrom, effectiveTo, shift, p.getT1(), p.getT2()));
    }

    private Mono<ReportResult> attachCritical(Long siteId, LocalDateTime from, LocalDateTime to, ReportShift shift,
            MainAggregates main, ExtraAggregates extras) {
        CategoryCount critical = main.bySite().stream().filter(c -> c.total() > 0).findFirst().orElse(null);
        if (critical == null) {
            return Mono.just(new ReportResult(buildSummary(main.total(), main.previousTotal(), main.bySite()),
                    main.byEppType(), main.bySite(), main.dailyTrend(), main.byZone(), extras.topWorkers(),
                    extras.notifications(), main.hourlyCounts(), extras.weekdayCounts(), extras.zoneHour(), null));
        }
        long previousTotal = main.bySitePrev().stream().filter(c -> c.label().equals(critical.label()))
                .mapToLong(CategoryCount::total).findFirst().orElse(0L);
        Mono<Optional<String>> topEpp = reportRepo.topEppForSite(critical.label(), from, to, shift)
                .map(Optional::of).defaultIfEmpty(Optional.empty());
        Mono<Optional<String>> topZone = reportRepo.topZoneForSite(critical.label(), from, to, shift)
                .map(Optional::of).defaultIfEmpty(Optional.empty());
        return Mono.zip(topEpp, topZone).map(t -> new ReportResult(
                buildSummary(main.total(), main.previousTotal(), main.bySite()), main.byEppType(), main.bySite(),
                main.dailyTrend(), main.byZone(), extras.topWorkers(), extras.notifications(), main.hourlyCounts(),
                extras.weekdayCounts(), extras.zoneHour(), new CriticalDetail(critical.label(), critical.total(),
                        t.getT1().orElse(null), t.getT2().orElse(null),
                        variationPct(critical.total(), previousTotal))));
    }

    /** Agregados base (8 consultas en paralelo) — reemplaza el Tuple8 posicional. */
    private record MainAggregates(long total, long previousTotal, List<CategoryCount> byEppType,
            List<CategoryCount> bySite, List<CategoryCount> bySitePrev, List<DailyCount> dailyTrend,
            List<ZoneCount> byZone, List<HourlyCount> hourlyCounts) {}

    /** Agregados extra (4 consultas en paralelo). */
    private record ExtraAggregates(List<WorkerCount> topWorkers, NotificationHealth notifications,
            List<WeekdayCount> weekdayCounts, List<ZoneHourCount> zoneHour) {}
}
