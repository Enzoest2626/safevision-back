package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceRepositoryPort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.application.ports.out.WorkerRepositoryPort;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Evidence;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.infrastructure.web.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.web.dto.CvIncidentMessage;
import com.safevision.back.infrastructure.web.dto.IncidentRequest;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orquesta la ingesta de incidentes EPP: persiste incidente + evidencia y
 * delega la notificación de la obra a {@link IncidentNotificationService}.
 * Las consultas de incidentes ya registrados viven en {@link IncidentQueryService}.
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

    public IncidentService(IncidentRepositoryPort incidentRepo,
                            EvidenceRepositoryPort evidenceRepo,
                            WorkerRepositoryPort workerRepo,
                            CameraRepositoryPort cameraRepo,
                            SiteRepositoryPort siteRepo,
                            IncidentNotificationService notificationService) {
        this.incidentRepo = incidentRepo;
        this.evidenceRepo = evidenceRepo;
        this.workerRepo = workerRepo;
        this.cameraRepo = cameraRepo;
        this.siteRepo = siteRepo;
        this.notificationService = notificationService;
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
                .flatMap(resolved -> persistIncidentAndEvidence(resolved.getT1(), resolved.getT2(), resolved.getT3(),
                        UUID.randomUUID().toString(), request.missingEpp().toArray(new String[0]),
                        request.timestamp(), request.frameB64(), null, traceId))
                .map(IncidentResponse::from);
    }

    /**
     * Registra un incidente recibido por POST /api/v1/cv/incidents (ver
     * IncidentController) — misma resolución worker/cámara/obra que
     * {@link #register}, pero la foto ya está en S3 (no llega inline) y el
     * incidente queda correlacionado por {@code externalId} para que el clip
     * de video (que llega después) lo pueda encontrar.
     */
    public Mono<IncidentResponse> registerFromCv(CvIncidentMessage message, String traceId) {
        log.info("Incidente recibido del CV | trace_id={} incident_id={} worker_code={} camera_code={} site={}",
                traceId, message.incidentId(), message.workerCode(), message.cameraCode(), message.siteName());

        Mono<Worker> workerMono = workerRepo.findByCode(message.workerCode())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Worker desconocido: " + message.workerCode())));
        Mono<Camera> cameraMono = cameraRepo.findByCode(message.cameraCode())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cámara desconocida: " + message.cameraCode())));
        Mono<Site> siteMono = siteRepo.findByName(message.siteName())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Obra desconocida: " + message.siteName())));

        return Mono.zip(workerMono, cameraMono, siteMono)
                .flatMap(resolved -> persistIncidentAndEvidence(resolved.getT1(), resolved.getT2(), resolved.getT3(),
                        message.incidentId(), message.missingEpp().toArray(new String[0]),
                        message.timestamp(), null, message.photoS3Key(), traceId))
                .map(IncidentResponse::from);
    }

    /**
     * Cola común de {@link #register} y {@link #registerFromCv}: arma y persiste
     * el {@link Incident}, guarda su {@link Evidence} (foto inline o en S3, según
     * cuál de los dos parámetros venga no nulo) y delega la notificación.
     */
    private Mono<Incident> persistIncidentAndEvidence(Worker worker, Camera camera, Site site, String externalId,
                                                        String[] missingEpp, LocalDateTime occurredAt,
                                                        String frameB64, String photoS3Key, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        Incident incident = new Incident(null, worker.id(), camera.id(), site.id(),
                externalId, missingEpp, occurredAt, now);

        return incidentRepo.save(incident)
                .doOnNext(saved -> log.info(
                        "Incidente persistido | trace_id={} id={} external_id={} worker_id={} site_id={} camera_id={}",
                        traceId, saved.id(), saved.externalId(), saved.workerId(), saved.siteId(), saved.cameraId()))
                .flatMap(saved -> evidenceRepo.save(new Evidence(null, saved.id(), "PHOTO",
                                frameB64, photoS3Key, null, null, now))
                        .flatMap(evidence -> notificationService.notify(saved, camera, site, evidence, traceId)
                                .thenReturn(saved)));
    }

    /**
     * Registra el clip de video de un incidente ya notificado (POST separado,
     * llega minutos después). No dispara una segunda notificación Telegram —
     * la foto ya se envió, el clip es evidencia adicional.
     */
    public Mono<Void> registerClipReady(CvClipReadyMessage message) {
        return incidentRepo.findByExternalId(message.incidentId())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Incidente desconocido para clip: external_id=" + message.incidentId())))
                .flatMap(incident -> evidenceRepo.save(new Evidence(null, incident.id(), "VIDEO",
                        null, message.s3Key(), message.durationSeconds(), message.fileSizeBytes(),
                        LocalDateTime.now())))
                .doOnNext(evidence -> log.info("Clip de video registrado | incident_id={} storage_key={}",
                        message.incidentId(), message.s3Key()))
                .then();
    }
}
