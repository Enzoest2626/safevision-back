package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.application.ports.out.NotificationChannelPort;
import com.safevision.back.application.ports.out.NotificationChannelRepositoryPort;
import com.safevision.back.application.ports.out.NotificationRepositoryPort;
import com.safevision.back.application.ports.out.NotificationStatusRepositoryPort;
import com.safevision.back.application.ports.out.SiteContactRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Notification;
import com.safevision.back.domain.model.NotificationChannel;
import com.safevision.back.domain.model.NotificationStatus;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.infrastructure.config.TelegramProperties;
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

/** Reporte diario (HU12, CA1/CA2): mensaje con eventos, mensaje vacío y fallo de Telegram contenido. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSchedulerService — reporte diario automático por Telegram")
class ReportSchedulerServiceTest {

    @Mock private SiteRepositoryPort siteRepo;
    @Mock private IncidentRepositoryPort incidentRepo;
    @Mock private CameraRepositoryPort cameraRepo;
    @Mock private SiteContactRepositoryPort siteContactRepo;
    @Mock private NotificationChannelPort channel;
    @Mock private NotificationRepositoryPort notificationRepo;
    @Mock private NotificationChannelRepositoryPort channelRepo;
    @Mock private NotificationStatusRepositoryPort statusRepo;

    private ReportSchedulerService service;

    private final Site site = new Site(1L, "OBRA-1", "Main-Site", "Lima", 30, true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Incident morning = new Incident(100L, 10L, 20L, 1L, "ext-100",
            new String[]{"helmet"}, LocalDate.now().atTime(8, 12), LocalDateTime.now());
    private final Incident afternoon = new Incident(101L, 10L, 20L, 1L, "ext-101",
            new String[]{"vest"}, LocalDate.now().atTime(9, 45), LocalDateTime.now());
    private final SiteContact contact = new SiteContact(5L, 1L, "Supervisor", "999999999", "111222333",
            null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final NotificationChannel telegramChannel = new NotificationChannel(1L, "TELEGRAM", "Telegram");
    private final NotificationStatus sentStatus = new NotificationStatus(2L, "SENT", "Enviado");
    private final NotificationStatus failedStatus = new NotificationStatus(3L, "FAILED", "Fallido");

    @BeforeEach
    void setUp() {
        service = new ReportSchedulerService(siteRepo, incidentRepo, cameraRepo, siteContactRepo, channel,
                notificationRepo, channelRepo, statusRepo, new TelegramProperties("bot-token", "GLOBAL-CHAT"));
        lenient().when(siteRepo.findByActiveTrue()).thenReturn(Flux.just(site));
        lenient().when(channelRepo.findByCode("TELEGRAM")).thenReturn(Mono.just(telegramChannel));
        lenient().when(statusRepo.findByCode("SENT")).thenReturn(Mono.just(sentStatus));
        lenient().when(statusRepo.findByCode("FAILED")).thenReturn(Mono.just(failedStatus));
        lenient().when(notificationRepo.save(any(Notification.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    @Test
    @DisplayName("CA1 — con eventos del día: manda el reporte con total, EPP, cámara y hora, y registra SENT")
    void conEventos_mandaReporteConTotalesYLineas() {
        when(incidentRepo.countByFilter(eq(1L), isNull(), any(), any())).thenReturn(Mono.just(2L));
        when(incidentRepo.findByFilterPaged(eq(1L), isNull(), any(), any(), eq(2), eq(0L)))
                .thenReturn(Flux.just(morning, afternoon));
        when(cameraRepo.findAllById(anyList())).thenReturn(Flux.just(camera));
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L))
                .thenReturn(Flux.just(contact));
        when(channel.sendTextMessage(eq("111222333"), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendDailyReports()).verifyComplete();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(channel).sendTextMessage(eq("111222333"), text.capture());
        assertThat(text.getValue())
                .contains("Main-Site")
                .contains("Total de incumplimientos: 2")
                .contains("Por EPP")
                .contains("helmet: 1").contains("vest: 1")
                .contains("Por cámara")
                .contains("Entrada: 2")
                // El detalle por evento vive en /alertas, no en el reporte diario.
                .doesNotContain("08:12").doesNotContain("09:45");
        verify(notificationRepo, times(2)).save(argThat(n ->
                n.statusId().equals(sentStatus.id()) && n.sentAt() != null));
    }

    @Test
    @DisplayName("CA2 — día vacío: igual se manda, indicando que no hubo incumplimientos")
    void diaVacio_mandaMensajeSinIncumplimientos() {
        when(incidentRepo.countByFilter(eq(1L), isNull(), any(), any())).thenReturn(Mono.just(0L));
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L))
                .thenReturn(Flux.empty());
        when(channel.sendTextMessage(eq("GLOBAL-CHAT"), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendDailyReports()).verifyComplete();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(channel).sendTextMessage(eq("GLOBAL-CHAT"), text.capture());
        assertThat(text.getValue()).contains("Sin incumplimientos");
        // Sin incidentes no hay fila a la cual atar el envío (notifications.incident_id es NOT NULL).
        verify(notificationRepo, never()).save(any(Notification.class));
    }

    @Test
    @DisplayName("Error de Telegram: registra FAILED con el detalle, sin propagar la excepción")
    void errorTelegram_registraFailedSinPropagar() {
        when(incidentRepo.countByFilter(eq(1L), isNull(), any(), any())).thenReturn(Mono.just(1L));
        when(incidentRepo.findByFilterPaged(eq(1L), isNull(), any(), any(), eq(1), eq(0L)))
                .thenReturn(Flux.just(morning));
        when(cameraRepo.findAllById(anyList())).thenReturn(Flux.just(camera));
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L))
                .thenReturn(Flux.empty());
        when(channel.sendTextMessage(eq("GLOBAL-CHAT"), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("Telegram respondió 500")));

        StepVerifier.create(service.sendDailyReports()).verifyComplete();

        verify(notificationRepo).save(argThat(n ->
                n.statusId().equals(failedStatus.id()) && n.errorMsg().contains("500")));
    }
}
