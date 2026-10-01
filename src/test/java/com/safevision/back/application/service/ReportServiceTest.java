package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.dto.report.CategoryCount;
import com.safevision.back.application.dto.report.DailyCount;
import com.safevision.back.application.dto.report.HourlyCount;
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportShift;
import com.safevision.back.application.dto.report.WeekdayCount;
import com.safevision.back.application.dto.report.ZoneHourCount;
import com.safevision.back.application.ports.out.ReportRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — agregación de reportes")
class ReportServiceTest {

    @Mock
    private ReportRepositoryPort reportRepo;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(reportRepo);
    }

    private void stubEmptyExcept(long total, long previousTotal) {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(total),
                Mono.just(previousTotal));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));
    }

    @Test
    @DisplayName("tendencia sube cuando el total actual supera al periodo anterior")
    void tendencia_sube() {
        stubEmptyExcept(10, 5);

        StepVerifier.create(service.buildReport(1L, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.summary().totalIncidents()).isEqualTo(10);
                    assertThat(result.summary().trendDirection()).isEqualTo("UP");
                    assertThat(result.summary().trendPct()).isEqualTo(100.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("tendencia baja cuando el total actual es menor al periodo anterior")
    void tendencia_baja() {
        stubEmptyExcept(2, 8);

        StepVerifier.create(service.buildReport(1L, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.summary().trendDirection()).isEqualTo("DOWN");
                    assertThat(result.summary().trendPct()).isEqualTo(75.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sin incidentes en ningún periodo, la tendencia es plana y no inventa un porcentaje")
    void tendencia_sinDatosEnNingunPeriodo_esPlana() {
        stubEmptyExcept(0, 0);

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.summary().trendDirection()).isEqualTo("FLAT");
                    assertThat(result.summary().trendPct()).isEqualTo(0.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sin base en el periodo anterior, la dirección es 'sube' pero el porcentaje queda null")
    void tendencia_sinBaseAnterior_pctEsNull() {
        stubEmptyExcept(3, 0);

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.summary().trendDirection()).isEqualTo("UP");
                    assertThat(result.summary().trendPct()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("la obra crítica es la primera con total > 0 del breakdown por obra")
    void obraCritica_esLaPrimeraConIncidentes() {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(7L), Mono.just(7L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any()))
                .thenReturn(Flux.just(new CategoryCount("Torre Central", 7), new CategoryCount("Puente Norte", 0)))
                .thenReturn(Flux.just(new CategoryCount("Torre Central", 7), new CategoryCount("Puente Norte", 0)));
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));
        when(reportRepo.topEppForSite(eq("Torre Central"), any(), any(), any())).thenReturn(Mono.just("helmet"));
        when(reportRepo.topZoneForSite(eq("Torre Central"), any(), any(), any())).thenReturn(Mono.just("Piso 2"));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.summary().criticalSiteName()).isEqualTo("Torre Central");
                    assertThat(result.summary().criticalSiteTotal()).isEqualTo(7);
                    assertThat(result.bySite()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sin ninguna obra con incidentes, la obra crítica queda null (no inventa una)")
    void obraCritica_sinIncidentes_esNull() {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(0L), Mono.just(0L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any()))
                .thenReturn(Flux.just(new CategoryCount("Torre Central", 0)));
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> assertThat(result.summary().criticalSiteName()).isNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("la tendencia diaria rellena con 0 los días sin incidentes en el rango")
    void tendenciaDiaria_rellenaDiasVacios() {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(2L), Mono.just(0L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any(), any()))
                .thenReturn(Flux.just(new DailyCount(LocalDate.of(2026, 8, 3), 2)));
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 3, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.dailyTrend()).hasSize(3);
                    assertThat(result.dailyTrend()).extracting(DailyCount::day).containsExactly(
                            LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3));
                    assertThat(result.dailyTrend()).extracting(DailyCount::total).containsExactly(0L, 0L, 2L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sin from/to explícitos, usa el default de últimos 30 días")
    void sinFechas_usaDefaultDeUltimos30Dias() {
        stubEmptyExcept(0, 0);

        StepVerifier.create(service.buildReport(null, null, null, null)).assertNext(result -> {}).verifyComplete();

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportRepo).countByEppType(isNull(), fromCaptor.capture(), toCaptor.capture(), isNull());

        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
                fromCaptor.getValue().toLocalDate(), toCaptor.getValue().toLocalDate());
        assertThat(daysBetween).isEqualTo(30);
    }

    @Test
    @DisplayName("propaga siteId a todas las consultas del puerto")
    void propagaSiteIdATodasLasConsultas() {
        stubEmptyExcept(0, 0);
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 7, 23, 59);

        StepVerifier.create(service.buildReport(42L, from, to, null)).assertNext(result -> {}).verifyComplete();

        verify(reportRepo).countByEppType(eq(42L), eq(from), eq(to), isNull());
        verify(reportRepo).countBySite(eq(42L), eq(from), eq(to), isNull());
        verify(reportRepo).countByZone(eq(42L), eq(from), eq(to), isNull());
        verify(reportRepo).topWorkers(eq(42L), eq(from), eq(to), eq(5), isNull());
    }

    @Test
    @DisplayName("hourlyCounts trae las 24 horas con zero-fill aunque solo haya incidentes en dos")
    void hourlyCounts_zeroFill24Horas() {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(4L), Mono.just(0L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any()))
                .thenReturn(Flux.just(new HourlyCount(8, 3), new HourlyCount(14, 1)));
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 1, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.hourlyCounts()).hasSize(24);
                    assertThat(result.hourlyCounts()).extracting(HourlyCount::hour)
                            .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
                                    18, 19, 20, 21, 22, 23);
                    assertThat(result.hourlyCounts().get(8).total()).isEqualTo(3);
                    assertThat(result.hourlyCounts().get(14).total()).isEqualTo(1);
                    assertThat(result.hourlyCounts().get(0).total()).isZero();
                    assertThat(result.weekdayCounts()).hasSize(7);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("weekdayCounts trae los 7 días con zero-fill")
    void weekdayCounts_zeroFill7Dias() {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(2L), Mono.just(0L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any()))
                .thenReturn(Flux.just(new WeekdayCount(3, 2)));
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.weekdayCounts()).hasSize(7);
                    assertThat(result.weekdayCounts()).extracting(WeekdayCount::weekday)
                            .containsExactly(1, 2, 3, 4, 5, 6, 7);
                    assertThat(result.weekdayCounts().get(2).total()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("shift=morning se propaga como ReportShift.MORNING a todas las consultas")
    void shiftMorning_sePropagaAlPuerto() {
        stubEmptyExcept(0, 0);
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 7, 23, 59);

        StepVerifier.create(service.buildReport(null, from, to, "morning"))
                .assertNext(result -> {}).verifyComplete();

        verify(reportRepo).countIncidents(isNull(), eq(from), eq(to), eq(ReportShift.MORNING));
        verify(reportRepo).countByHour(isNull(), eq(from), eq(to), eq(ReportShift.MORNING));
        verify(reportRepo).countByZoneHour(isNull(), eq(from), eq(to), eq(ReportShift.MORNING));
        verify(reportRepo).notificationHealth(isNull(), eq(from), eq(to), eq(ReportShift.MORNING));
    }

    @Test
    @DisplayName("shift con otro valor responde 400 sin tocar el repositorio")
    void shiftInvalido_responde400() {
        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), "noche"))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("criticalDetail explica la obra crítica: EPP y zona dominantes más variación %")
    void criticalDetail_explicaObraCritica() {
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(7L), Mono.just(5L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any()))
                .thenReturn(Flux.just(new CategoryCount("Torre Central", 7), new CategoryCount("Puente Norte", 0)))
                .thenReturn(Flux.just(new CategoryCount("Torre Central", 5), new CategoryCount("Puente Norte", 1)));
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any()))
                .thenReturn(Flux.just(new ZoneHourCount("Piso 2", 8, 4)));
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(0, 0, null, List.of())));
        when(reportRepo.topEppForSite(eq("Torre Central"), any(), any(), any())).thenReturn(Mono.just("helmet"));
        when(reportRepo.topZoneForSite(eq("Torre Central"), any(), any(), any())).thenReturn(Mono.just("Piso 2"));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.criticalDetail().site()).isEqualTo("Torre Central");
                    assertThat(result.criticalDetail().total()).isEqualTo(7);
                    assertThat(result.criticalDetail().topEpp()).isEqualTo("helmet");
                    assertThat(result.criticalDetail().topZone()).isEqualTo("Piso 2");
                    assertThat(result.criticalDetail().variationPct()).isEqualTo(40.0);
                    assertThat(result.zoneHour()).hasSize(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sin obra crítica, criticalDetail queda null")
    void criticalDetail_sinObraCritica_esNull() {
        stubEmptyExcept(0, 0);

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> assertThat(result.criticalDetail()).isNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("notifications trae último fallo y obras con fallos")
    void notifications_conFailingSites() {
        LocalDateTime lastFailed = LocalDateTime.of(2026, 8, 5, 10, 30);
        when(reportRepo.countIncidents(any(), any(), any(), any())).thenReturn(Mono.just(4L), Mono.just(4L));
        when(reportRepo.countByEppType(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByWeekday(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZoneHour(any(), any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt(), any())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any(), any()))
                .thenReturn(Mono.just(new NotificationHealth(10, 2, lastFailed, List.of("Torre Central"))));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 7, 23, 59), null))
                .assertNext(result -> {
                    assertThat(result.notifications().total()).isEqualTo(10);
                    assertThat(result.notifications().failed()).isEqualTo(2);
                    assertThat(result.notifications().lastFailedAt()).isEqualTo(lastFailed);
                    assertThat(result.notifications().failingSites()).containsExactly("Torre Central");
                })
                .verifyComplete();
    }
}
