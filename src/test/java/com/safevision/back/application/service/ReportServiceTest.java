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
import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.WorkerCount;
import com.safevision.back.application.dto.report.ZoneCount;
import com.safevision.back.application.ports.out.ReportRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — agregación de HU12")
class ReportServiceTest {

    @Mock
    private ReportRepositoryPort reportRepo;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(reportRepo);
    }

    private void stubEmptyExcept(long total, long previousTotal) {
        when(reportRepo.countIncidents(any(), any(), any())).thenReturn(Mono.just(total), Mono.just(previousTotal));
        when(reportRepo.countByEppType(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any())).thenReturn(Mono.just(new NotificationHealth(0, 0)));
    }

    @Test
    @DisplayName("tendencia sube cuando el total actual supera al periodo anterior")
    void tendencia_sube() {
        stubEmptyExcept(10, 5);

        StepVerifier.create(service.buildReport(1L, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59)))
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

        StepVerifier.create(service.buildReport(1L, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59)))
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

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59)))
                .assertNext(result -> {
                    assertThat(result.summary().trendDirection()).isEqualTo("FLAT");
                    assertThat(result.summary().trendPct()).isEqualTo(0.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sin base en el periodo anterior, la dirección es 'sube' pero el porcentaje queda null (no se inventa)")
    void tendencia_sinBaseAnterior_pctEsNull() {
        stubEmptyExcept(3, 0);

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59)))
                .assertNext(result -> {
                    assertThat(result.summary().trendDirection()).isEqualTo("UP");
                    assertThat(result.summary().trendPct()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("la obra crítica es la primera con total > 0 del breakdown por obra")
    void obraCritica_esLaPrimeraConIncidentes() {
        when(reportRepo.countIncidents(any(), any(), any())).thenReturn(Mono.just(7L), Mono.just(7L));
        when(reportRepo.countByEppType(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any())).thenReturn(Flux.just(
                new CategoryCount("Torre Central", 7), new CategoryCount("Puente Norte", 0)));
        when(reportRepo.countByDay(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any())).thenReturn(Mono.just(new NotificationHealth(0, 0)));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59)))
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
        when(reportRepo.countIncidents(any(), any(), any())).thenReturn(Mono.just(0L), Mono.just(0L));
        when(reportRepo.countByEppType(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any())).thenReturn(Flux.just(new CategoryCount("Torre Central", 0)));
        when(reportRepo.countByDay(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByZone(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any())).thenReturn(Mono.just(new NotificationHealth(0, 0)));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 7, 23, 59)))
                .assertNext(result -> assertThat(result.summary().criticalSiteName()).isNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("la tendencia diaria rellena con 0 los días sin incidentes en el rango")
    void tendenciaDiaria_rellenaDiasVacios() {
        when(reportRepo.countIncidents(any(), any(), any())).thenReturn(Mono.just(2L), Mono.just(0L));
        when(reportRepo.countByEppType(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countBySite(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.countByDay(any(), any(), any())).thenReturn(Flux.just(new DailyCount(LocalDate.of(2026, 8, 3), 2)));
        when(reportRepo.countByZone(any(), any(), any())).thenReturn(Flux.empty());
        when(reportRepo.topWorkers(any(), any(), any(), anyInt())).thenReturn(Flux.empty());
        when(reportRepo.notificationHealth(any(), any(), any())).thenReturn(Mono.just(new NotificationHealth(0, 0)));

        StepVerifier.create(service.buildReport(null, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 3, 23, 59)))
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

        StepVerifier.create(service.buildReport(null, null, null)).assertNext(result -> {}).verifyComplete();

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportRepo).countByEppType(isNull(), fromCaptor.capture(), toCaptor.capture());

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

        StepVerifier.create(service.buildReport(42L, from, to)).assertNext(result -> {}).verifyComplete();

        verify(reportRepo).countByEppType(eq(42L), eq(from), eq(to));
        verify(reportRepo).countBySite(eq(42L), eq(from), eq(to));
        verify(reportRepo).countByZone(eq(42L), eq(from), eq(to));
        verify(reportRepo).topWorkers(eq(42L), eq(from), eq(to), eq(5));
    }
}
