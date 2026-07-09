package com.safevision.back.service;

import com.safevision.back.dto.IncidentRequest;
import com.safevision.back.dto.IncidentResponse;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Evidence;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Notification;
import com.safevision.back.model.Site;
import com.safevision.back.model.SiteContact;
import com.safevision.back.model.Worker;
import com.safevision.back.repository.CameraRepository;
import com.safevision.back.repository.EvidenceRepository;
import com.safevision.back.repository.IncidentRepository;
import com.safevision.back.repository.NotificationChannelRepository;
import com.safevision.back.repository.NotificationRepository;
import com.safevision.back.repository.NotificationStatusRepository;
import com.safevision.back.model.Zone;
import com.safevision.back.repository.SiteContactRepository;
import com.safevision.back.repository.SiteRepository;
import com.safevision.back.repository.WorkerRepository;
import com.safevision.back.repository.ZoneRepository;
import com.safevision.back.config.TelegramProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orquesta el registro de incidentes EPP (HU10): persiste incidente + evidencia,
 * notifica a los contactos de la obra vía Telegram, y registra el resultado en `notifications`.
 */
@Service
public class IncidentService {

    private static final String CHANNEL_TELEGRAM = "TELEGRAM";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";

    private final IncidentRepository incidentRepo;
    private final EvidenceRepository evidenceRepo;
    private final NotificationRepository notificationRepo;
    private final NotificationChannelRepository channelRepo;
    private final NotificationStatusRepository statusRepo;
    private final WorkerRepository workerRepo;
    private final CameraRepository cameraRepo;
    private final SiteRepository siteRepo;
    private final SiteContactRepository siteContactRepo;
    private final ZoneRepository zoneRepo;
    private final TelegramNotificationService telegramService;
    private final TelegramProperties telegramProperties;

    public IncidentService(IncidentRepository incidentRepo,
                            EvidenceRepository evidenceRepo,
                            NotificationRepository notificationRepo,
                            NotificationChannelRepository channelRepo,
                            NotificationStatusRepository statusRepo,
                            WorkerRepository workerRepo,
                            CameraRepository cameraRepo,
                            SiteRepository siteRepo,
                            SiteContactRepository siteContactRepo,
                            ZoneRepository zoneRepo,
                            TelegramNotificationService telegramService,
                            TelegramProperties telegramProperties) {
        this.incidentRepo = incidentRepo;
        this.evidenceRepo = evidenceRepo;
        this.notificationRepo = notificationRepo;
        this.channelRepo = channelRepo;
        this.statusRepo = statusRepo;
        this.workerRepo = workerRepo;
        this.cameraRepo = cameraRepo;
        this.siteRepo = siteRepo;
        this.siteContactRepo = siteContactRepo;
        this.zoneRepo = zoneRepo;
        this.telegramService = telegramService;
        this.telegramProperties = telegramProperties;
    }

    public Mono<IncidentResponse> register(IncidentRequest request) {
        Mono<Worker> workerMono = workerRepo.findByCode(request.workerCode())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Worker desconocido: " + request.workerCode())));
        Mono<Camera> cameraMono = cameraRepo.findByCode(request.cameraCode())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cámara desconocida: " + request.cameraCode())));
        Mono<Site> siteMono = siteRepo.findByName(request.siteName())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Obra desconocida: " + request.siteName())));

        return Mono.zip(workerMono, cameraMono, siteMono)
                .flatMap(resolved -> {
                    Worker worker = resolved.getT1();
                    Camera camera = resolved.getT2();
                    Site site = resolved.getT3();
                    LocalDateTime now = LocalDateTime.now();
                    Incident incident = new Incident(null, worker.id(), camera.id(), site.id(),
                            request.missingEpp().toArray(new String[0]), request.timestamp(), now);

                    return incidentRepo.save(incident)
                            .flatMap(saved -> evidenceRepo.save(new Evidence(null, saved.id(), request.frameB64(), now))
                                    .thenReturn(saved))
                            .flatMap(saved -> notifyTelegram(saved, camera, site, request.frameB64())
                                    .thenReturn(saved));
                })
                .map(IncidentResponse::from);
    }

    public Flux<IncidentResponse> findByFilter(Long siteId, Long workerId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        return incidentRepo.findByFilter(siteId, workerId, effectiveFrom, effectiveTo)
                .map(IncidentResponse::from);
    }

    public Mono<IncidentResponse> findById(Long id) {
        return incidentRepo.findById(id)
                .map(IncidentResponse::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found")));
    }

    private Mono<Void> notifyTelegram(Incident incident, Camera camera, Site site, String frameB64) {
        return Mono.zip(resolveChatIds(site.id()), resolveZoneName(camera))
                .flatMap(resolved -> {
                    List<String> chatIds = resolved.getT1();
                    String zoneName = resolved.getT2();
                    if (chatIds.isEmpty()) {
                        return recordNotification(incident.id(), STATUS_FAILED,
                                "Sin chat de Telegram configurado para la obra");
                    }
                    return Flux.fromIterable(chatIds)
                            .flatMap(chatId -> sendAndRecord(chatId, incident, camera, site, zoneName, frameB64))
                            .then();
                });
    }

    private Mono<String> resolveZoneName(Camera camera) {
        if (camera.zoneId() == null) {
            return Mono.just("");
        }
        return zoneRepo.findById(camera.zoneId()).map(Zone::name).defaultIfEmpty("");
    }

    /** Contactos de la obra con Telegram configurado; si no hay, cae al chat global (fail-safe). */
    private Mono<List<String>> resolveChatIds(Long siteId) {
        return siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(siteId)
                .map(SiteContact::telegramChatId)
                .collectList()
                .map(chatIds -> {
                    if (!chatIds.isEmpty()) {
                        return chatIds;
                    }
                    String fallback = telegramProperties.chatId();
                    return (fallback != null && !fallback.isBlank()) ? List.of(fallback) : List.<String>of();
                });
    }

    private Mono<Void> sendAndRecord(String chatId, Incident incident, Camera camera, Site site,
                                      String zoneName, String frameB64) {
        return telegramService.sendIncidentAlert(chatId, incident, camera, site, zoneName, frameB64)
                .then(recordNotification(incident.id(), STATUS_SENT, null))
                .onErrorResume(ex -> recordNotification(incident.id(), STATUS_FAILED, ex.getMessage()));
    }

    private Mono<Void> recordNotification(Long incidentId, String statusCode, String errorMsg) {
        return Mono.zip(channelRepo.findByCode(CHANNEL_TELEGRAM), statusRepo.findByCode(statusCode))
                .flatMap(codes -> {
                    LocalDateTime now = LocalDateTime.now();
                    Notification notification = new Notification(
                            null, incidentId, codes.getT1().id(), codes.getT2().id(),
                            STATUS_SENT.equals(statusCode) ? now : null,
                            errorMsg, now
                    );
                    return notificationRepo.save(notification);
                })
                .then();
    }
}
