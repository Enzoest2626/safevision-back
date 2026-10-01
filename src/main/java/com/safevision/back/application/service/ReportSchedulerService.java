package com.safevision.back.application.service;

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
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.infrastructure.config.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Reporte diario automático por Telegram (HU12): al llegar la hora configurada genera, por cada
 * obra activa, el resumen de los incumplimientos del día y lo manda a sus contactos Telegram
 * (o al chat global). Sin eventos igual se manda. Solo existe si {@code app.reports.daily-enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "app.reports", name = "daily-enabled", havingValue = "true", matchIfMissing = false)
public class ReportSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(ReportSchedulerService.class);
    private static final String CHANNEL_TELEGRAM = "TELEGRAM";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";

    private final SiteRepositoryPort siteRepo;
    private final IncidentRepositoryPort incidentRepo;
    private final CameraRepositoryPort cameraRepo;
    private final SiteContactRepositoryPort siteContactRepo;
    private final NotificationChannelPort channel;
    private final NotificationRepositoryPort notificationRepo;
    private final NotificationChannelRepositoryPort channelRepo;
    private final NotificationStatusRepositoryPort statusRepo;
    private final TelegramProperties telegramProperties;

    public ReportSchedulerService(SiteRepositoryPort siteRepo, IncidentRepositoryPort incidentRepo,
                                   CameraRepositoryPort cameraRepo, SiteContactRepositoryPort siteContactRepo,
                                   NotificationChannelPort channel, NotificationRepositoryPort notificationRepo,
                                   NotificationChannelRepositoryPort channelRepo,
                                   NotificationStatusRepositoryPort statusRepo,
                                   TelegramProperties telegramProperties) {
        this.siteRepo = siteRepo;
        this.incidentRepo = incidentRepo;
        this.cameraRepo = cameraRepo;
        this.siteContactRepo = siteContactRepo;
        this.channel = channel;
        this.notificationRepo = notificationRepo;
        this.channelRepo = channelRepo;
        this.statusRepo = statusRepo;
        this.telegramProperties = telegramProperties;
    }

    /** Disparo programado — delega en {@link #sendDailyReports()} y se suscribe (void, como el poller). */
    @Scheduled(cron = "${app.reports.daily-cron:0 55 23 * * *}", zone = "America/Lima")
    public void sendDailyReport() {
        sendDailyReports().subscribe();
    }

    /** Reporte del día para todas las obras activas; el fallo de una no tumba el resto. */
    public Mono<Void> sendDailyReports() {
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        return siteRepo.findByActiveTrue()
                .concatMap(site -> sendSiteReport(site, from, to)
                        .onErrorResume(ex -> {
                            log.error("Fallo el reporte diario | site_id={} error={}", site.id(), ex.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sendSiteReport(Site site, LocalDateTime from, LocalDateTime to) {
        return incidentsOfDay(site.id(), from, to)
                .flatMap(incidents -> Mono.zip(resolveChatIds(site.id()), cameraNames(incidents), channelRefs())
                        .flatMap(tuple -> {
                            if (tuple.getT1().isEmpty()) {
                                log.warn("Reporte diario sin destinatarios | site_id={}", site.id());
                                return Mono.empty();
                            }
                            String text = DailyReportMessage.build(site, from.toLocalDate(), incidents,
                                    tuple.getT2());
                            return Flux.fromIterable(tuple.getT1())
                                    .concatMap(chatId -> sendAndRecord(chatId, incidents, text, tuple.getT3()))
                                    .then();
                        }));
    }

    /** Eventos del día con los filtros que ya expone el puerto (siteId/from/to). */
    private Mono<List<Incident>> incidentsOfDay(Long siteId, LocalDateTime from, LocalDateTime to) {
        return incidentRepo.countByFilter(siteId, null, from, to)
                .flatMap(total -> total == 0 ? Mono.<List<Incident>>just(List.of())
                        : incidentRepo.findByFilterPaged(siteId, null, from, to, Math.toIntExact(total), 0)
                                .collectList());
    }

    /** Nombres de cámara por id (fallback al code) para las líneas del reporte. */
    private Mono<Map<Long, String>> cameraNames(List<Incident> incidents) {
        List<Long> ids = incidents.stream().map(Incident::cameraId).distinct().toList();
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return cameraRepo.findAllById(ids)
                .collectMap(Camera::id, c -> c.name() != null && !c.name().isBlank() ? c.name() : c.code());
    }

    /** Contactos de la obra con Telegram; si no hay, cae al chat global (igual que el aviso por incidente). */
    private Mono<List<String>> resolveChatIds(Long siteId) {
        return siteContactRepo.findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(siteId)
                .map(SiteContact::telegramChatId)
                .collectList()
                .map(chatIds -> {
                    if (!chatIds.isEmpty()) {
                        return chatIds;
                    }
                    String fallback = telegramProperties.chatId();
                    return fallback != null && !fallback.isBlank() ? List.of(fallback) : List.<String>of();
                });
    }

    /** Ids del canal TELEGRAM y de los estados SENT/FAILED — se resuelven una vez por obra. */
    private Mono<NotificationRefs> channelRefs() {
        return Mono.zip(channelRepo.findByCode(CHANNEL_TELEGRAM),
                        statusRepo.findByCode(STATUS_SENT), statusRepo.findByCode(STATUS_FAILED))
                .map(t -> new NotificationRefs(t.getT1().id(), t.getT2().id(), t.getT3().id()));
    }

    private Mono<Void> sendAndRecord(String chatId, List<Incident> incidents, String text, NotificationRefs refs) {
        return channel.sendTextMessage(chatId, text)
                .then(recordAll(incidents, refs, true, null))
                .onErrorResume(ex -> {
                    log.error("Fallo el reporte diario | chat_id={} error={}", chatId, ex.getMessage());
                    return recordAll(incidents, refs, false, ex.getMessage());
                });
    }

    /** Una fila por incidente cubierto. Día vacío no registra: incident_id es NOT NULL y no hay a qué atarlo. */
    private Mono<Void> recordAll(List<Incident> incidents, NotificationRefs refs, boolean sent, String errorMsg) {
        LocalDateTime now = LocalDateTime.now();
        return Flux.fromIterable(incidents)
                .concatMap(incident -> notificationRepo.save(new Notification(null, incident.id(),
                        refs.channelId(), sent ? refs.sentId() : refs.failedId(), sent ? now : null,
                        errorMsg, now)))
                .then();
    }

    private record NotificationRefs(Long channelId, Long sentId, Long failedId) {}
}
