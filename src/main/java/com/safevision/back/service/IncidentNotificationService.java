package com.safevision.back.service;

import com.safevision.back.config.TelegramProperties;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Notification;
import com.safevision.back.model.Site;
import com.safevision.back.model.SiteContact;
import com.safevision.back.model.Zone;
import com.safevision.back.repository.NotificationChannelRepository;
import com.safevision.back.repository.NotificationRepository;
import com.safevision.back.repository.NotificationStatusRepository;
import com.safevision.back.repository.SiteContactRepository;
import com.safevision.back.repository.ZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resuelve los contactos de la obra, envía la alerta Telegram por incidente EPP (HU10)
 * y registra el resultado (SENT/FAILED) en `notifications`. Extraído de IncidentService
 * para mantener ambas clases por debajo del límite de 150 líneas.
 */
@Service
public class IncidentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotificationService.class);

    private static final String CHANNEL_TELEGRAM = "TELEGRAM";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";

    private final NotificationRepository notificationRepo;
    private final NotificationChannelRepository channelRepo;
    private final NotificationStatusRepository statusRepo;
    private final SiteContactRepository siteContactRepo;
    private final ZoneRepository zoneRepo;
    private final TelegramNotificationService telegramService;
    private final TelegramProperties telegramProperties;

    public IncidentNotificationService(NotificationRepository notificationRepo,
                                        NotificationChannelRepository channelRepo,
                                        NotificationStatusRepository statusRepo,
                                        SiteContactRepository siteContactRepo,
                                        ZoneRepository zoneRepo,
                                        TelegramNotificationService telegramService,
                                        TelegramProperties telegramProperties) {
        this.notificationRepo = notificationRepo;
        this.channelRepo = channelRepo;
        this.statusRepo = statusRepo;
        this.siteContactRepo = siteContactRepo;
        this.zoneRepo = zoneRepo;
        this.telegramService = telegramService;
        this.telegramProperties = telegramProperties;
    }

    public Mono<Void> notify(Incident incident, Camera camera, Site site, String frameB64) {
        return Mono.zip(resolveChatIds(site.id()), resolveZoneName(camera))
                .flatMap(resolved -> {
                    List<String> chatIds = resolved.getT1();
                    String zoneName = resolved.getT2();
                    if (chatIds.isEmpty()) {
                        log.warn("Sin contactos Telegram configurados | incident_id={} site_id={}",
                                incident.id(), site.id());
                        return recordNotification(incident.id(), STATUS_FAILED,
                                "Sin chat de Telegram configurado para la obra");
                    }
                    log.info("Contactos Telegram resueltos | incident_id={} chats={}", incident.id(), chatIds.size());
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
                .doOnSuccess(v -> log.info("Notificación Telegram enviada | incident_id={} chat_id={}",
                        incident.id(), chatId))
                .then(recordNotification(incident.id(), STATUS_SENT, null))
                .onErrorResume(ex -> {
                    log.error("Fallo notificación Telegram | incident_id={} chat_id={} error={}",
                            incident.id(), chatId, ex.getMessage());
                    return recordNotification(incident.id(), STATUS_FAILED, ex.getMessage());
                });
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
