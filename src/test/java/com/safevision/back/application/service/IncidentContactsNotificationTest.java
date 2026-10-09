package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.EvidenceStoragePort;
import com.safevision.back.application.ports.out.NotificationChannelPort;
import com.safevision.back.application.ports.out.NotificationChannelRepositoryPort;
import com.safevision.back.application.ports.out.NotificationRepositoryPort;
import com.safevision.back.application.ports.out.NotificationStatusRepositoryPort;
import com.safevision.back.application.ports.out.SiteContactRepositoryPort;
import com.safevision.back.application.ports.out.ZoneRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Evidence;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Notification;
import com.safevision.back.domain.model.NotificationChannel;
import com.safevision.back.domain.model.NotificationStatus;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CP43 (HU08, CA2) — los datos de contacto registrados son los que se usan para
 * enviar las notificaciones: solo los contactos ACTIVOS con telegram_chat_id de
 * la obra, y el chat global del entorno únicamente como fallback cuando la obra
 * no tiene ninguno.
 *
 * <p>IncidentNotificationService es REAL con todos sus puertos mockeados
 * (mismo armado que IncidentNotificationServiceTest).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentContactsNotificationTest — notificación solo a supervisores activos (HU08, CA2)")
class IncidentContactsNotificationTest {

    @Mock private NotificationRepositoryPort notificationRepo;
    @Mock private NotificationChannelRepositoryPort channelRepo;
    @Mock private NotificationStatusRepositoryPort statusRepo;
    @Mock private SiteContactRepositoryPort siteContactRepo;
    @Mock private ZoneRepositoryPort zoneRepo;
    @Mock private NotificationChannelPort telegramService;
    @Mock private EvidenceStoragePort presignService;

    private IncidentNotificationService service;
    private TelegramProperties telegramProperties;

    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Site site = new Site(1L, "OBRA-1", "Main-Site", "Lima", 60, true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Worker worker = new Worker(10L, 1L, 3, "Juan", "Perez", "Albañil",
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Incident incident = new Incident(100L, 10L, 20L, 1L, "ext-100",
            new String[]{"helmet", "vest"}, LocalDateTime.of(2026, 6, 24, 13, 30), LocalDateTime.now());
    private final Evidence evidenceInline = new Evidence(1L, 100L, "PHOTO",
            "ZmFrZS1mcmFtZQ==", null, null, null, LocalDateTime.now());
    private final NotificationChannel telegramChannel = new NotificationChannel(1L, "TELEGRAM", "Telegram");
    private final NotificationStatus sentStatus = new NotificationStatus(2L, "SENT", "Enviado");

    @BeforeEach
    void setUp() {
        // El chat global está SIEMPRE configurado en estos tests: es la prueba
        // de que no se usa mientras la obra tenga contactos propios.
        telegramProperties = new TelegramProperties("bot-token", "GLOBAL-CHAT");
        service = new IncidentNotificationService(notificationRepo, channelRepo, statusRepo,
                siteContactRepo, zoneRepo, telegramService, telegramProperties, presignService);

        lenient().when(notificationRepo.save(any(Notification.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(channelRepo.findByCode("TELEGRAM")).thenReturn(Mono.just(telegramChannel));
        lenient().when(statusRepo.findByCode("SENT")).thenReturn(Mono.just(sentStatus));
    }

    // ── CP43 — contactos activos ──────────────────────────────────────

    @Test
    @Tag("CP43")
    @DisplayName("CP43 — obra con 2 contactos activos: se notifica a sus chats y nunca al chat global")
    void cp43_contactosActivos_notificaASusChats_yNoAlGlobal() {
        System.out.println("[CP43] Obra con 2 contactos ACTIVOS con telegram_chat_id (chat-A, chat-B) — "
                + "chat global de entorno = GLOBAL-CHAT");
        SiteContact supervisorA = new SiteContact(5L, 1L, "Supervisor A", "999888777", "chat-A", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        SiteContact supervisorB = new SiteContact(6L, 1L, "Supervisor B", "999777666", "chat-B", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L))
                .thenReturn(Flux.just(supervisorA, supervisorB));
        when(telegramService.sendIncidentAlert(anyString(), any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.notify(incident, worker, camera, site, evidenceInline, "trace-cp43"))
                .verifyComplete();

        // La consulta usada es exactamente la de contactos activos con chat_id
        verify(siteContactRepo).findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L);
        verifyNoMoreInteractions(siteContactRepo);

        ArgumentCaptor<String> chats = ArgumentCaptor.forClass(String.class);
        verify(telegramService, times(2))
                .sendIncidentAlert(chats.capture(), any(), any(), any(), any(), anyString(), anyString());

        ArgumentCaptor<Notification> notificaciones = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo, times(2)).save(notificaciones.capture());

        List<String> notificados = chats.getAllValues();
        System.out.println("       consulta usada     = findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)");
        System.out.println("       chats notificados  = " + notificados);
        System.out.println("       notificaciones     = " + notificaciones.getAllValues().size()
                + " (statusId=" + sentStatus.id() + " SENT, sentAt no nulo)");
        System.out.println("       chat global usado  = " + (notificados.contains("GLOBAL-CHAT") ? "sí" : "no"));

        assertThat(notificados).as("un envío por cada contacto activo").containsExactly("chat-A", "chat-B");
        assertThat(notificados).as("el chat global no se usa cuando hay contactos")
                .doesNotContain("GLOBAL-CHAT");
        assertThat(notificaciones.getAllValues())
                .as("una notificación SENT por cada contacto")
                .hasSize(2)
                .allMatch(n -> sentStatus.id().equals(n.statusId()) && n.sentAt() != null);

        System.out.println("[CP43] Se notificó exactamente a [chat-A, chat-B], se registraron 2 notificaciones "
                + "SENT y el chat global no se usó => PASA");
    }

    // ── CP43 — fallback al chat global ────────────────────────────────

    @Test
    @Tag("CP43")
    @DisplayName("CP43 — obra sin contactos activos: se notifica solo al chat global de entorno (fallback)")
    void cp43_sinContactosActivos_usaSoloElChatGlobal() {
        System.out.println("[CP43] Obra sin contactos Telegram — fallback al chat global de entorno = GLOBAL-CHAT");
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.notify(incident, worker, camera, site, evidenceInline, "trace-cp43"))
                .verifyComplete();

        // Misma consulta de contactos activos, ahora sin resultados
        verify(siteContactRepo).findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L);
        verifyNoMoreInteractions(siteContactRepo);

        ArgumentCaptor<String> chats = ArgumentCaptor.forClass(String.class);
        verify(telegramService, times(1))
                .sendIncidentAlert(chats.capture(), any(), any(), any(), any(), anyString(), anyString());

        ArgumentCaptor<Notification> notificaciones = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepo, times(1)).save(notificaciones.capture());

        System.out.println("       consulta usada     = findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)");
        System.out.println("       chats notificados  = " + chats.getAllValues());
        System.out.println("       notificaciones     = " + notificaciones.getAllValues().size()
                + " (statusId=" + sentStatus.id() + " SENT, sentAt no nulo)");

        assertThat(chats.getAllValues()).as("solo el chat global como fallback").containsExactly("GLOBAL-CHAT");
        Notification registrada = notificaciones.getValue();
        assertThat(registrada.statusId()).as("estado de la notificación").isEqualTo(sentStatus.id());
        assertThat(registrada.sentAt()).as("sentAt de la notificación").isNotNull();

        System.out.println("[CP43] Se notificó únicamente al chat global GLOBAL-CHAT (fallback) y se registró "
                + "1 notificación SENT => PASA");
    }
}
