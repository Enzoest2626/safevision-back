package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.EvidenceRepositoryPort;
import com.safevision.back.application.ports.out.EvidenceStoragePort;
import com.safevision.back.application.ports.out.IncidentRepositoryPort;
import com.safevision.back.infrastructure.web.dto.EvidenceResponse;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import com.safevision.back.infrastructure.web.dto.PagedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Consultas de incidentes ya registrados. Separado de IncidentService
 * (ingesta + notificación) para mantener ambas clases por debajo del
 * límite de 150 líneas — mismo criterio que {@link IncidentNotificationService}.
 */
@Service
public class IncidentQueryService {

    private static final Logger log = LoggerFactory.getLogger(IncidentQueryService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final IncidentRepositoryPort incidentRepo;
    private final EvidenceRepositoryPort evidenceRepo;
    private final EvidenceStoragePort presignService;

    public IncidentQueryService(IncidentRepositoryPort incidentRepo, EvidenceRepositoryPort evidenceRepo,
                                 EvidenceStoragePort presignService) {
        this.incidentRepo = incidentRepo;
        this.evidenceRepo = evidenceRepo;
        this.presignService = presignService;
    }

    /**
     * Página de incidentes que matchean el filtro — {@code page} arranca en
     * 1; {@code size} default 20, clamp a 50 (evita que un cliente pida
     * páginas gigantes de un tirón).
     */
    public Mono<PagedResponse<IncidentResponse>> findByFilter(Long siteId, Long workerId,
                                                               LocalDateTime from, LocalDateTime to,
                                                               Integer page, Integer size) {
        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime effectiveTo = to != null ? to : LocalDateTime.now();
        int effectivePage = (page != null && page > 0) ? page : 1;
        int effectiveSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        long offset = (long) (effectivePage - 1) * effectiveSize;

        Mono<List<IncidentResponse>> itemsMono = incidentRepo
                .findByFilterPaged(siteId, workerId, effectiveFrom, effectiveTo, effectiveSize, offset)
                .map(IncidentResponse::from)
                .collectList();
        Mono<Long> countMono = incidentRepo.countByFilter(siteId, workerId, effectiveFrom, effectiveTo);

        return Mono.zip(itemsMono, countMono)
                .map(tuple -> PagedResponse.of(tuple.getT1(), effectivePage, effectiveSize, tuple.getT2()));
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
