package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceStoragePort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.application.ports.out.WorkerRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Evidence;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.infrastructure.messaging.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.messaging.dto.CvIncidentMessage;
import com.safevision.back.infrastructure.web.dto.EvidenceResponse;
import com.safevision.back.infrastructure.web.dto.IncidentRequest;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orquesta el registro de incidentes EPP (HU10): persiste incidente + evidencia
 * y delega la notificación de la obra a {@link IncidentNotificationService}.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepositoryPort incidentRepo;
    private final EvidenceRepositoryPort evidenceRepo;
    private final WorkerRepositoryPort workerRepo;
    private final CameraRepositoryPort cameraRepo;
    private final SiteRepositoryPort siteRepo;
    private final IncidentNotificationService notificationService;
    private final EvidenceStoragePort presignService;

    public IncidentService(IncidentRepositoryPort incidentRepo,
                            EvidenceRepositoryPort evidenceRepo,
                            WorkerRepositoryPort workerRepo,
                            CameraRepositoryPort cameraRepo,
                            SiteRepositoryPort siteRepo,
                            IncidentNotificationService notificationService,
                            EvidenceStoragePort presignService) {
        this.incidentRepo = incidentRepo;
        this.evidenceRepo = evidenceRepo;
        this.workerRepo = workerRepo;
        this.cameraRepo = cameraRepo;
        this.siteRepo = siteRepo;
        this.notificationService = notificationService;
        this.presignService = presignService;
    }

    public Mono<IncidentResponse> register(IncidentRequest request, String traceId) {
        log.info("Evento recibido desde CV | trace_id={} worker_code={} camera_code={} site={} missing_epp={}",
                traceId, request.workerCode(), request.cameraCode(), request.siteName(), request.missingEpp());

        Mono<Worker> workerMono = workerRepo.findByCode(request.workerCode())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Incidente rechazado | trace_id={} worker_code={} no existe", traceId, request.workerCode());
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Worker desconocido: " + request.workerCode()));
                }));
        Mono<Camera> cameraMono = cameraRepo.findByCode(request.cameraCode())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Incidente rechazado | trace_id={} camera_code={} no existe", traceId, request.cameraCode());
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Cámara desconocida: " + request.cameraCode()));
                }));
        Mono<Site> siteMono = siteRepo.findByName(request.siteName())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Incidente rechazado | trace_id={} site={} no existe", traceId, request.siteName());
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Obra desconocida: " + request.siteName()));
                }));

        return Mono.zip(workerMono, cameraMono, siteMono)
                .flatMap(resolved -> {
                    Worker worker = resolved.getT1();
                    Camera camera = resolved.getT2();
                    Site site = resolved.getT3();
                    LocalDateTime now = LocalDateTime.now();
                    Incident incident = new Incident(null, worker.id(), camera.id(), site.id(),
                            UUID.randomUUID().toString(),
                            request.missingEpp().toArray(new String[0]), request.timestamp(), now);

                    return incidentRepo.save(incident)
                            .doOnNext(saved -> log.info("Incidente persistido | trace_id={} id={} worker_id={} site_id={} camera_id={}",
                                    traceId, saved.id(), saved.workerId(), saved.siteId(), saved.cameraId()))
                            .flatMap(saved -> evidenceRepo.save(new Evidence(null, saved.id(), "PHOTO",
                                            request.frameB64(), null, null, null, now))
                                    .flatMap(evidence -> notificationService.notify(saved, camera, site, evidence, traceId)
                                            .thenReturn(saved)));
                })
                .map(IncidentResponse::from);
    }

    /**
     * Registra un incidente publicado por MQTT (ver MqttIncidentSubscriber) —
     * misma resolución worker/cámara/obra que {@link #register}, pero la foto
     * ya está en S3 (no llega inline) y el incidente queda correlacionado por
     * {@code externalId} para que el clip de video (que llega después) lo
     * pueda encontrar.
     */
    public Mono<IncidentResponse> registerFromCv(CvIncidentMessage message, String traceId) {
        log.info("Incidente recibido por MQTT | trace_id={} incident_id={} worker_code={} camera_code={} site={}",
                traceId, message.incidentId(), message.workerCode(), message.cameraCode(), message.siteName());

        Mono<Worker> workerMono = workerRepo.findByCode(message.workerCode())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Worker desconocido: " + message.workerCode())));
        Mono<Camera> cameraMono = cameraRepo.findByCode(message.cameraCode())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Cámara desconocida: " + message.cameraCode())));
        Mono<Site> siteMono = siteRepo.findByName(message.siteName())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Obra desconocida: " + message.siteName())));

        return Mono.zip(workerMono, cameraMono, siteMono)
                .flatMap(resolved -> {
                    Worker worker = resolved.getT1();
                    Camera camera = resolved.getT2();
                    Site site = resolved.getT3();
                    LocalDateTime now = LocalDateTime.now();
                    Incident incident = new Incident(null, worker.id(), camera.id(), site.id(),
                            message.incidentId(), message.missingEpp().toArray(new String[0]),
                            message.timestamp(), now);

                    return incidentRepo.save(incident)
                            .doOnNext(saved -> log.info("Incidente MQTT persistido | trace_id={} id={} external_id={}",
                                    traceId, saved.id(), saved.externalId()))
                            .flatMap(saved -> evidenceRepo.save(new Evidence(null, saved.id(), "PHOTO",
                                            null, message.photoS3Key(), null, null, now))
                                    .flatMap(evidence -> notificationService.notify(saved, camera, site, evidence, traceId)
                                            .thenReturn(saved)));
                })
                .map(IncidentResponse::from);
    }

    /**
     * Registra el clip de video de un incidente ya notificado (mensaje MQTT
     * separado, llega minutos después). No dispara una segunda notificación
     * Telegram — la foto ya se envió, el clip es evidencia adicional.
     */
    public Mono<Void> registerClipReady(CvClipReadyMessage message) {
        return incidentRepo.findByExternalId(message.incidentId())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Incidente desconocido para clip: external_id=" + message.incidentId())))
                .flatMap(incident -> evidenceRepo.save(new Evidence(null, incident.id(), "VIDEO",
                        null, message.s3Key(), message.durationSeconds(), message.fileSizeBytes(),
                        LocalDateTime.now())))
                .doOnNext(evidence -> log.info("Clip de video registrado | incident_id={} storage_key={}",
                        message.incidentId(), message.s3Key()))
                .then();
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
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Incidente no encontrado | id={}", id);
                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
                }));
    }

    /**
     * Evidencia (foto/clip) de un incidente, con URL prefirmada de S3 cuando
     * corresponde ({@code storageKey != null}) — {@code null} para filas
     * legacy que solo tienen {@code frameB64} inline.
     */
    public Flux<EvidenceResponse> findEvidenceByIncidentId(Long incidentId) {
        return incidentRepo.findById(incidentId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found")))
                .thenMany(Flux.defer(() -> evidenceRepo.findByIncidentId(incidentId)))
                .map(evidence -> EvidenceResponse.from(evidence,
                        evidence.storageKey() != null ? presignService.presignGetUrl(evidence.storageKey()) : null));
    }
}
