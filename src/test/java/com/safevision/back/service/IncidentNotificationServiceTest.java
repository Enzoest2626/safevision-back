package com.safevision.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.config.TelegramProperties;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Notification;
import com.safevision.back.model.NotificationChannel;
import com.safevision.back.model.NotificationStatus;
import com.safevision.back.model.Site;
import com.safevision.back.model.SiteContact;
import com.safevision.back.model.Zone;
import com.safevision.back.repository.NotificationChannelRepository;
import com.safevision.back.repository.NotificationRepository;
import com.safevision.back.repository.NotificationStatusRepository;
import com.safevision.back.repository.SiteContactRepository;
import com.safevision.back.repository.ZoneRepository;
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

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentNotificationService — HU10 (resolución de contactos + alerta Telegram)")
class IncidentNotificationServiceTest {

    @Mock private NotificationRepository notificationRepo;
    @Mock private NotificationChannelRepository channelRepo;
    @Mock private NotificationStatusRepository statusRepo;
    @Mock private SiteContactRepository siteContactRepo;
    @Mock private ZoneRepository zoneRepo;
    @Mock private TelegramNotificationService telegramService;

    private IncidentNotificationService service;
    private TelegramProperties telegramProperties;

    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Site site = new Site(1L, "Main-Site", "Lima", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Incident incident = new Incident(100L, 10L, 20L, 1L,
            new String[]{"helmet", "vest"}, LocalDateTime.of(2026, 6, 24, 13, 30), LocalDateTime.now());
    private final NotificationChannel telegramChannel = new NotificationChannel(1L, "TELEGRAM", "Telegram");
    private final NotificationStatus sentStatus = new NotificationStatus(2L, "SENT", "Enviado");
    private final NotificationStatus failedStatus = new NotificationStatus(3L, "FAILED", "Fallido");

    @BeforeEach
    void setUp() {
        telegramProperties = new TelegramProperties("bot-token", "GLOBAL-CHAT");
        service = new IncidentNotificationService(notificationRepo, channelRepo, statusRepo,
                siteContactRepo, zoneRepo, telegramService, telegramProperties);

        lenient().when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(channelRepo.findByCode("TELEGRAM")).thenReturn(Mono.just(telegramChannel));
        lenient().when(statusRepo.findByCode("SENT")).thenReturn(Mono.just(sentStatus));
        lenient().when(statusRepo.findByCode("FAILED")).thenReturn(Mono.just(failedStatus));
    }

    @Test
    @DisplayName("Obra con contacto Telegram → envía al chat del contacto y registra SENT")
    void notify_conContactoDeObra_notificaSent() {
        SiteContact contact = new SiteContact(5L, 1L, "Supervisor", "999999999", "111222333",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L))
                .thenReturn(Flux.just(contact));
        when(telegramService.sendIncidentAlert(eq("111222333"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.notify(incident, camera, site, "ZmFrZS1mcmFtZQ==", "trace-test"))
                .verifyComplete();

        verify(telegramService).sendIncidentAlert(eq("111222333"), any(), any(), any(), anyString(), anyString());
        verify(notificationRepo).save(argThat(n -> n.statusId().equals(sentStatus.id())));
    }

    @Test
    @DisplayName("Cámara con zona asignada → el nombre de zona viaja en la alerta de Telegram")
    void notify_camaraConZona_incluyeNombreDeZona() {
        Camera cameraConZona = new Camera(20L, 1L, 7L, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        Zone zone = new Zone(7L, 1L, "Piso 2 - Construcción", true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(zoneRepo.findById(7L)).thenReturn(Mono.just(zone));
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(anyString(), any(), any(), any(), eq("Piso 2 - Construcción"), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.notify(incident, cameraConZona, site, "ZmFrZS1mcmFtZQ==", "trace-test"))
                .verifyComplete();

        verify(telegramService).sendIncidentAlert(
                anyString(), any(), any(), any(), eq("Piso 2 - Construcción"), anyString());
    }

    @Test
    @DisplayName("Obra sin contactos Telegram → usa el chat_id global como fallback")
    void notify_sinContactosDeObra_usaFallbackGlobal() {
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.notify(incident, camera, site, "ZmFrZS1mcmFtZQ==", "trace-test"))
                .verifyComplete();

        verify(telegramService).sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Sin contactos de obra ni chat_id global configurado → registra FAILED sin llamar a Telegram")
    void notify_sinContactosNiFallback_registraFailedSinLlamarATelegram() {
        TelegramProperties sinFallback = new TelegramProperties("bot-token", null);
        service = new IncidentNotificationService(notificationRepo, channelRepo, statusRepo,
                siteContactRepo, zoneRepo, telegramService, sinFallback);
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());

        StepVerifier.create(service.notify(incident, camera, site, "ZmFrZS1mcmFtZQ==", "trace-test"))
                .verifyComplete();

        verify(telegramService, never()).sendIncidentAlert(anyString(), any(), any(), any(), anyString(), anyString());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(captor.capture());
        assertThat(captor.getValue().statusId()).isEqualTo(failedStatus.id());
        assertThat(captor.getValue().errorMsg()).contains("Sin chat de Telegram configurado");
    }

    @Test
    @DisplayName("Falla el envío a Telegram → notificación queda FAILED con el detalle del error")
    void notify_fallaTelegram_persisteNotificacionFailed() {
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("Telegram respondió 401")));

        StepVerifier.create(service.notify(incident, camera, site, "ZmFrZS1mcmFtZQ==", "trace-test"))
                .verifyComplete();

        verify(notificationRepo).save(argThat(n ->
                n.statusId().equals(failedStatus.id()) && n.errorMsg().contains("401")));
    }

    // ── CP29: Bot de Telegram inaccesible / rate-limit ────────────────────────

    @Test
    @DisplayName("CP29 — Bot de Telegram con rate-limit (mockeado) registra FAILED sin pérdida silenciosa")
    void cp29_botTelegramRateLimit_registraFailedSinPerdidaSilenciosa() {
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("Telegram respondió 429: Too Many Requests")));

        StepVerifier.create(service.notify(incident, camera, site, "ZmFrZS1mcmFtZQ==", "trace-test"))
                .verifyComplete();

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo).save(notificationCaptor.capture());
        Notification saved = notificationCaptor.getValue();

        boolean registradoFailed = saved.statusId().equals(failedStatus.id())
                && saved.errorMsg() != null && saved.errorMsg().contains("429");

        System.out.println("\n[CP29] Bot de Telegram inaccesible (rate-limit 429, mockeado):");
        System.out.println("       incidentId persistido  = " + saved.incidentId());
        System.out.println("       notification.statusId  = " + saved.statusId() + " (FAILED=" + failedStatus.id() + ")");
        System.out.println("       notification.errorMsg  = " + saved.errorMsg());
        System.out.println("       Nota: IncidentNotificationService no implementa reintento — onErrorResume va directo a FAILED.");
        System.out.println("[CP29] Registro FAILED sin pérdida silenciosa del evento: " + registradoFailed + " => PASA");

        assertThat(saved.statusId()).isEqualTo(failedStatus.id());
        assertThat(saved.errorMsg()).contains("429");
    }
}
