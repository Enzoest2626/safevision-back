package com.safevision.back.application.service;

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
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.domain.model.Zone;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resuelve los contactos de la obra, envía la alerta Telegram por incidente EPP
 * y registra el resultado (SENT/FAILED) en `notifications`. Extraído de IncidentService
 * para mantener ambas clases por debajo del límite de 150 líneas.
 */
@Service
public class IncidentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotificationService.class);

    private static final String CHANNEL_TELEGRAM = "TELEGRAM";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";

    private final NotificationRepositoryPort notificationRepo;
    private final NotificationChannelRepositoryPort channelRepo;
    private final NotificationStatusRepositoryPort statusRepo;
    private final SiteContactRepositoryPort siteContactRepo;
    private final ZoneRepositoryPort zoneRepo;
    private final NotificationChannelPort telegramService;
    private final TelegramProperties telegramProperties;
    private final EvidenceStoragePort presignService;

    public IncidentNotificationService(NotificationRepositoryPort notificationRepo,
                                        NotificationChannelRepositoryPort channelRepo,
                                        NotificationStatusRepositoryPort statusRepo,
                                        SiteContactRepositoryPort siteContactRepo,
                                        ZoneRepositoryPort zoneRepo,
                                        NotificationChannelPort telegramService,
                                        TelegramProperties telegramProperties,
                                        EvidenceStoragePort presignService) {
        this.notificationRepo = notificationRepo;
        this.channelRepo = channelRepo;
        this.statusRepo = statusRepo;
        this.siteContactRepo = siteContactRepo;
        this.zoneRepo = zoneRepo;
        this.telegramService = telegramService;
        this.telegramProperties = telegramProperties;
        this.presignService = presignService;
    }

    /**
     * Notifica un incidente a los contactos Telegram de la obra. La foto se
     * manda por URL prefirmada si {@code evidence} referencia S3
     * ({@code storageKey}, flujo CV nuevo), o por bytes inline si trae
     * {@code frameB64} (flujo HTTP legacy, sin cambios de comportamiento).
     */
    public Mono<Void> notify(Incident incident, Worker worker, Camera camera, Site site, Evidence evidence,
                             String traceId) {
        return Mono.zip(resolveChatIds(site.id()), resolveZoneName(camera))
                .flatMap(resolved -> {
                    List<String> chatIds = resolved.getT1();
                    String zoneName = resolved.getT2();
                    if (chatIds.isEmpty()) {
                        log.warn("Sin contactos Telegram configurados | trace_id={} incident_id={} site_id={}",
                                traceId, incident.id(), site.id());
                        return recordNotification(incident.id(), STATUS_FAILED,
                                "Sin chat de Telegram configurado para la obra");
                    }
                    log.info("Contactos Telegram resueltos | trace_id={} incident_id={} chats={}",
                            traceId, incident.id(), chatIds.size());
                    return Flux.fromIterable(chatIds)
                            .flatMap(chatId -> sendAndRecord(chatId, incident, worker, camera, site, zoneName,
                                    evidence, traceId))
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

    private Mono<Void> sendAndRecord(String chatId, Incident incident, Worker worker, Camera camera, Site site,
                                      String zoneName, Evidence evidence, String traceId) {
        Mono<Void> send = evidence.storageKey() != null
                ? telegramService.sendIncidentAlertByUrl(chatId, incident, worker, camera, site, zoneName,
                        presignService.presignGetUrl(evidence.storageKey()))
                : telegramService.sendIncidentAlert(chatId, incident, worker, camera, site, zoneName,
                        evidence.frameB64());

        return send
                .doOnSuccess(v -> log.info("Notificación Telegram enviada | trace_id={} incident_id={} chat_id={}",
                        traceId, incident.id(), chatId))
                .then(recordNotification(incident.id(), STATUS_SENT, null))
                .onErrorResume(ex -> {
                    log.error("Fallo notificación Telegram | trace_id={} incident_id={} chat_id={} error={}",
                            traceId, incident.id(), chatId, ex.getMessage());
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
