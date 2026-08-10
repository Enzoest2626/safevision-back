package com.safevision.back.service;

import com.safevision.back.dto.IncidentRequest;
import com.safevision.back.dto.IncidentResponse;
import com.safevision.back.model.Camera;
import com.safevision.back.model.Evidence;
import com.safevision.back.model.Incident;
import com.safevision.back.model.Site;
import com.safevision.back.model.Worker;
import com.safevision.back.repository.CameraRepository;
import com.safevision.back.repository.EvidenceRepository;
import com.safevision.back.repository.IncidentRepository;
import com.safevision.back.repository.SiteRepository;
import com.safevision.back.repository.WorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Orquesta el registro de incidentes EPP (HU10): persiste incidente + evidencia
 * y delega la notificación de la obra a {@link IncidentNotificationService}.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidentRepo;
    private final EvidenceRepository evidenceRepo;
    private final WorkerRepository workerRepo;
    private final CameraRepository cameraRepo;
    private final SiteRepository siteRepo;
    private final IncidentNotificationService notificationService;

    public IncidentService(IncidentRepository incidentRepo,
                            EvidenceRepository evidenceRepo,
                            WorkerRepository workerRepo,
                            CameraRepository cameraRepo,
                            SiteRepository siteRepo,
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
                .flatMap(resolved -> {
                    Worker worker = resolved.getT1();
                    Camera camera = resolved.getT2();
                    Site site = resolved.getT3();
                    LocalDateTime now = LocalDateTime.now();
                    Incident incident = new Incident(null, worker.id(), camera.id(), site.id(),
                            request.missingEpp().toArray(new String[0]), request.timestamp(), now);

                    return incidentRepo.save(incident)
                            .doOnNext(saved -> log.info("Incidente persistido | trace_id={} id={} worker_id={} site_id={} camera_id={}",
                                    traceId, saved.id(), saved.workerId(), saved.siteId(), saved.cameraId()))
                            .flatMap(saved -> evidenceRepo.save(new Evidence(null, saved.id(), request.frameB64(), now))
                                    .thenReturn(saved))
                            .flatMap(saved -> notificationService.notify(saved, camera, site, request.frameB64(), traceId)
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
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Incidente no encontrado | id={}", id);
                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
                }));
    }
}
