package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.EvidenceRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceStoragePort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.infrastructure.web.dto.EvidenceResponse;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Consultas de incidentes ya registrados. Separado de IncidentService
 * (ingesta + notificación) para mantener ambas clases por debajo del
 * límite de 150 líneas — mismo criterio que {@link IncidentNotificationService}.
 */
@Service
public class IncidentQueryService {

    private static final Logger log = LoggerFactory.getLogger(IncidentQueryService.class);

    private final IncidentRepositoryPort incidentRepo;
    private final EvidenceRepositoryPort evidenceRepo;
    private final EvidenceStoragePort presignService;

    public IncidentQueryService(IncidentRepositoryPort incidentRepo, EvidenceRepositoryPort evidenceRepo,
                                 EvidenceStoragePort presignService) {
        this.incidentRepo = incidentRepo;
        this.evidenceRepo = evidenceRepo;
        this.presignService = presignService;
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
