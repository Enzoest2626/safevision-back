package com.safevision.back.service;

import com.safevision.back.config.TelegramProperties;
import com.safevision.back.dto.IncidentRequest;
import com.safevision.back.dto.IncidentResponse;
import com.safevision.back.model.*;
import com.safevision.back.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentService — HU10 (registro de incidente + alerta Telegram)")
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepo;
    @Mock private EvidenceRepository evidenceRepo;
    @Mock private NotificationRepository notificationRepo;
    @Mock private NotificationChannelRepository channelRepo;
    @Mock private NotificationStatusRepository statusRepo;
    @Mock private WorkerRepository workerRepo;
    @Mock private CameraRepository cameraRepo;
    @Mock private SiteRepository siteRepo;
    @Mock private SiteContactRepository siteContactRepo;
    @Mock private ZoneRepository zoneRepo;
    @Mock private TelegramNotificationService telegramService;

    private IncidentService service;
    private TelegramProperties telegramProperties;

    private final Worker worker = new Worker(10L, 1L, 3, "Juan", "Perez", "Albañil",
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Camera camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
            true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Site site = new Site(1L, "Main-Site", "Lima", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final NotificationChannel telegramChannel = new NotificationChannel(1L, "TELEGRAM", "Telegram");
    private final NotificationStatus sentStatus = new NotificationStatus(2L, "SENT", "Enviado");
    private final NotificationStatus failedStatus = new NotificationStatus(3L, "FAILED", "Fallido");

    private IncidentRequest requestFor(String siteName) {
        return new IncidentRequest(3, List.of("helmet", "vest"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", siteName, "ZmFrZS1mcmFtZQ==");
    }

    @BeforeEach
    void setUp() {
        telegramProperties = new TelegramProperties("bot-token", "GLOBAL-CHAT");
        service = new IncidentService(incidentRepo, evidenceRepo, notificationRepo, channelRepo,
                statusRepo, workerRepo, cameraRepo, siteRepo, siteContactRepo, zoneRepo,
                telegramService, telegramProperties);

        lenient().when(workerRepo.findByCode(3)).thenReturn(Mono.just(worker));
        lenient().when(cameraRepo.findByCode("CAM-01")).thenReturn(Mono.just(camera));
        lenient().when(siteRepo.findByName("Main-Site")).thenReturn(Mono.just(site));
        lenient().when(incidentRepo.save(any(Incident.class)))
                .thenAnswer(inv -> {
                    Incident i = inv.getArgument(0);
                    return Mono.just(new Incident(100L, i.workerId(), i.cameraId(), i.siteId(),
                            i.missingEpp(), i.occurredAt(), i.createdAt()));
                });
        lenient().when(evidenceRepo.save(any(Evidence.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(notificationRepo.save(any(Notification.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(channelRepo.findByCode("TELEGRAM")).thenReturn(Mono.just(telegramChannel));
        lenient().when(statusRepo.findByCode("SENT")).thenReturn(Mono.just(sentStatus));
        lenient().when(statusRepo.findByCode("FAILED")).thenReturn(Mono.just(failedStatus));
    }

    @Test
    @DisplayName("Incidente válido con contacto Telegram de la obra → persiste y notifica SENT")
    void registraIncidente_conContactoDeObra_notificaSent() {
        SiteContact contact = new SiteContact(5L, 1L, "Supervisor", "999999999", "111222333",
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L))
                .thenReturn(Flux.just(contact));
        when(telegramService.sendIncidentAlert(eq("111222333"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Main-Site")))
                .assertNext(response -> {
                    assertThat(response.id()).isEqualTo(100L);
                    assertThat(response.workerId()).isEqualTo(10L);
                    assertThat(response.cameraId()).isEqualTo(20L);
                    assertThat(response.siteId()).isEqualTo(1L);
                    assertThat(response.missingEpp()).containsExactly("helmet", "vest");
                })
                .verifyComplete();

        verify(telegramService).sendIncidentAlert(eq("111222333"), any(), any(), any(), anyString(), anyString());
        verify(notificationRepo).save(argThat(n -> n.statusId().equals(sentStatus.id())));
    }

    @Test
    @DisplayName("Cámara con zona asignada → el nombre de zona viaja en la alerta de Telegram")
    void registraIncidente_camaraConZona_incluyeNombreDeZona() {
        Camera cameraConZona = new Camera(20L, 1L, 7L, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        Zone zone = new Zone(7L, 1L, "Piso 2 - Construcción", true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        when(cameraRepo.findByCode("CAM-01")).thenReturn(Mono.just(cameraConZona));
        when(zoneRepo.findById(7L)).thenReturn(Mono.just(zone));
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(anyString(), any(), any(), any(), eq("Piso 2 - Construcción"), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Main-Site")))
                .assertNext(response -> assertThat(response.id()).isEqualTo(100L))
                .verifyComplete();

        verify(telegramService).sendIncidentAlert(
                anyString(), any(), any(), any(), eq("Piso 2 - Construcción"), anyString());
    }

    @Test
    @DisplayName("Obra sin contactos Telegram → usa el chat_id global como fallback")
    void registraIncidente_sinContactosDeObra_usaFallbackGlobal() {
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Main-Site")))
                .assertNext(response -> assertThat(response.id()).isEqualTo(100L))
                .verifyComplete();

        verify(telegramService).sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Falla el envío a Telegram → incidente igual se registra y notificación queda FAILED")
    void registraIncidente_fallaTelegram_persisteIncidenteYNotificacionFallida() {
        when(siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(1L)).thenReturn(Flux.empty());
        when(telegramService.sendIncidentAlert(eq("GLOBAL-CHAT"), any(), any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("Telegram respondió 401")));

        StepVerifier.create(service.register(requestFor("Main-Site")))
                .assertNext(response -> assertThat(response.id()).isEqualTo(100L))
                .verifyComplete();

        verify(notificationRepo).save(argThat(n ->
                n.statusId().equals(failedStatus.id()) && n.errorMsg().contains("401")));
    }

    @Test
    @DisplayName("Worker desconocido → 400 BAD_REQUEST, nada se persiste")
    void registraIncidente_workerDesconocido_lanzaBadRequest() {
        when(workerRepo.findByCode(3)).thenReturn(Mono.empty());

        StepVerifier.create(service.register(requestFor("Main-Site")))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verify(incidentRepo, never()).save(any());
    }

    @Test
    @DisplayName("Incidente inexistente en GET /{id} → 404 NOT_FOUND")
    void buscarPorId_inexistente_lanzaNotFound() {
        when(incidentRepo.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(service.findById(999L))
                .expectErrorMatches(ex -> ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }
}
