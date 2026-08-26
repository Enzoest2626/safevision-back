package com.safevision.back.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.dto.report.NotificationHealth;
import com.safevision.back.application.dto.report.ReportResult;
import com.safevision.back.application.dto.report.ReportSummary;
import com.safevision.back.application.service.ReportService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportController — HTTP")
class ReportControllerTest {

    @Mock
    private ReportService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new ReportController(service)).build();
    }

    private ReportResult sampleResult() {
        return new ReportResult(
                new ReportSummary(5, "Torre Central", 3, 25.0, "UP"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new NotificationHealth(5, 1));
    }

    @Test
    @DisplayName("GET /api/v1/reports sin filtros retorna 200 con el reporte")
    void get_sinFiltros_retorna200() {
        when(service.buildReport(isNull(), isNull(), isNull())).thenReturn(Mono.just(sampleResult()));

        client.get().uri("/reports")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<ReportResult>>() {})
                .value(envelope -> {
                    org.assertj.core.api.Assertions.assertThat(envelope.error()).isFalse();
                    org.assertj.core.api.Assertions.assertThat(envelope.data().summary().totalIncidents()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("GET /api/v1/reports con siteId y rango de fechas los propaga al service")
    void get_conFiltros_losPropagaAlService() {
        when(service.buildReport(eq(7L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Mono.just(sampleResult()));

        client.get().uri("/reports?siteId=7&from=2026-08-01T00:00:00&to=2026-08-07T23:59:00")
                .exchange()
                .expectStatus().isOk();

        verify(service).buildReport(eq(7L),
                eq(LocalDateTime.of(2026, 8, 1, 0, 0)), eq(LocalDateTime.of(2026, 8, 7, 23, 59)));
    }
}
