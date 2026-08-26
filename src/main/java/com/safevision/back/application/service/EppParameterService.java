package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.EppParameterRepositoryPort;
import com.safevision.back.application.ports.out.RulesPublisherPort;
import com.safevision.back.application.ports.out.SiteEppConfigVersionRepositoryPort;
import com.safevision.back.application.ports.out.SiteEppRequirementRepositoryPort;
import com.safevision.back.domain.model.EppParameter;
import com.safevision.back.domain.model.SiteEppRequirement;
import com.safevision.back.infrastructure.web.dto.EppParameterRequest;
import com.safevision.back.infrastructure.web.dto.EppParameterResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EppParameterService {

    private final EppParameterRepositoryPort eppRepo;
    private final SiteEppRequirementRepositoryPort siteEppRepo;
    private final SiteEppConfigVersionRepositoryPort versionRepo;
    private final RulesPublisherPort rulesPublisher;

    public EppParameterService(EppParameterRepositoryPort eppRepo,
                               SiteEppRequirementRepositoryPort siteEppRepo,
                               SiteEppConfigVersionRepositoryPort versionRepo,
                               RulesPublisherPort rulesPublisher) {
        this.eppRepo = eppRepo;
        this.siteEppRepo = siteEppRepo;
        this.versionRepo = versionRepo;
        this.rulesPublisher = rulesPublisher;
    }

    /**
     * Retorna los EPPs requeridos para una obra.
     * Si la obra no tiene configuración explícita, se usa como fallback
     * todos los EPPs activos del catálogo global (fail-safe).
     */
    public Mono<EppParameterResponse> findBySite(Long siteId) {
        return siteEppRepo.findBySiteId(siteId)
                .flatMap(req -> eppRepo.findById(req.eppParameterId()))
                .collectList()
                .flatMap(eppParams -> {
                    if (eppParams.isEmpty()) {
                        // Fallback: todos los EPPs activos en el catálogo
                        return eppRepo.findByActiveTrue().collectList();
                    }
                    return Mono.just(eppParams);
                })
                .map(eppParams -> new EppParameterResponse(
                        siteId,
                        eppParams.stream()
                                .map(EppParameterResponse.EppItem::from)
                                .toList()
                ));
    }

    /**
     * Establece los EPPs requeridos para una obra.
     * Reemplaza completamente la configuración anterior de esa obra.
     */
    public Mono<EppParameterResponse> updateForSite(Long siteId,
                                                     EppParameterRequest request,
                                                     String updatedBy) {
        if (request.requiredEpp() == null || request.requiredEpp().isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "requiredEpp no puede estar vacío"));
        }
        return validateEppCodes(request.requiredEpp())
                .flatMap(validEpps -> acquireVersionLock(siteId).thenReturn(validEpps))
                .flatMap(validEpps -> replaceRequirements(siteId, validEpps, updatedBy))
                .map(validEpps -> toResponse(siteId, validEpps))
                .doOnSuccess(this::publishRules);
    }

    /** Valida que todos los códigos pedidos existan en el catálogo (sin efectos secundarios todavía). */
    private Mono<List<EppParameter>> validateEppCodes(List<String> requiredEpp) {
        return Flux.fromIterable(requiredEpp)
                .flatMap(code -> eppRepo.findByCode(code)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "EPP desconocido: " + code))))
                .collectList();
    }

    /**
     * CAS de versión — si otra solicitud ya modificó esta obra entre que
     * leímos la versión y este UPDATE, compareAndSwap afecta 0 filas y
     * switchIfEmpty dispara 409 sin tocar site_epp_requirements.
     */
    private Mono<Void> acquireVersionLock(Long siteId) {
        return versionRepo.ensureExists(siteId)
                .then(versionRepo.findById(siteId))
                .flatMap(v -> versionRepo.compareAndSwap(siteId, v.version()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                        "La configuración EPP de esta obra fue modificada por otra solicitud; reintente.")))
                .then();
    }

    /** Reemplaza completamente la configuración anterior de la obra por la nueva. */
    private Mono<List<EppParameter>> replaceRequirements(Long siteId, List<EppParameter> validEpps,
                                                          String updatedBy) {
        LocalDateTime now = LocalDateTime.now();
        List<SiteEppRequirement> newReqs = validEpps.stream()
                .map(epp -> new SiteEppRequirement(null, siteId, epp.id(), now, updatedBy))
                .toList();
        return siteEppRepo.deleteAllBySiteId(siteId)
                .thenMany(Flux.fromIterable(newReqs).flatMap(siteEppRepo::save))
                .then(Mono.just(validEpps));
    }

    private EppParameterResponse toResponse(Long siteId, List<EppParameter> validEpps) {
        return new EppParameterResponse(siteId,
                validEpps.stream().map(EppParameterResponse.EppItem::from).toList());
    }

    private void publishRules(EppParameterResponse response) {
        rulesPublisher.publishRules(response.siteId(),
                response.requiredEpp().stream().map(EppParameterResponse.EppItem::code).toList());
    }
}
