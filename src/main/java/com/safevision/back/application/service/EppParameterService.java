package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.EppParameterRepositoryPort;
import com.safevision.back.application.ports.out.RulesPublisherPort;
import com.safevision.back.application.ports.out.SiteEppConfigVersionRepositoryPort;
import com.safevision.back.application.ports.out.SiteEppRequirementRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.domain.model.EppParameter;
import com.safevision.back.domain.model.Site;
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

    /** Mismo default que {@code ComplianceTracker.DEFAULT_COOLDOWN_SECONDS} en el CV. */
    private static final int DEFAULT_COOLDOWN_SECONDS = 60;

    private final EppParameterRepositoryPort eppRepo;
    private final SiteEppRequirementRepositoryPort siteEppRepo;
    private final SiteEppConfigVersionRepositoryPort versionRepo;
    private final SiteRepositoryPort siteRepo;
    private final RulesPublisherPort rulesPublisher;

    public EppParameterService(EppParameterRepositoryPort eppRepo,
                               SiteEppRequirementRepositoryPort siteEppRepo,
                               SiteEppConfigVersionRepositoryPort versionRepo,
                               SiteRepositoryPort siteRepo,
                               RulesPublisherPort rulesPublisher) {
        this.eppRepo = eppRepo;
        this.siteEppRepo = siteEppRepo;
        this.versionRepo = versionRepo;
        this.siteRepo = siteRepo;
        this.rulesPublisher = rulesPublisher;
    }

    /**
     * Retorna los EPPs requeridos para una obra.
     * Si la obra no tiene configuración explícita, se usa como fallback
     * todos los EPPs activos del catálogo global (fail-safe).
     */
    public Mono<EppParameterResponse> findBySite(Long siteId) {
        Mono<List<EppParameter>> eppParamsMono = siteEppRepo.findBySiteId(siteId)
                .flatMap(req -> eppRepo.findById(req.eppParameterId()))
                .collectList()
                .flatMap(eppParams -> {
                    if (eppParams.isEmpty()) {
                        // Fallback: todos los EPPs activos en el catálogo
                        return eppRepo.findByActiveTrue().collectList();
                    }
                    return Mono.just(eppParams);
                });
        Mono<Integer> cooldownMono = siteRepo.findById(siteId)
                .map(Site::cooldownSeconds)
                .defaultIfEmpty(DEFAULT_COOLDOWN_SECONDS);
        return Mono.zip(eppParamsMono, cooldownMono)
                .map(tuple -> new EppParameterResponse(
                        siteId,
                        tuple.getT1().stream().map(EppParameterResponse.EppItem::from).toList(),
                        tuple.getT2()
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
                .flatMap(validEpps -> updateCooldown(siteId, request.cooldownSeconds(), updatedBy)
                        .thenReturn(toResponse(siteId, validEpps, request.cooldownSeconds())))
                .doOnSuccess(this::publishRules);
    }

    /** Actualiza el cooldown de la obra (columna `sites.cooldown_seconds`). */
    private Mono<Void> updateCooldown(Long siteId, int cooldownSeconds, String updatedBy) {
        return siteRepo.findById(siteId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")))
                .flatMap(site -> siteRepo.save(new Site(
                        site.id(), site.code(), site.name(), site.location(), cooldownSeconds, site.active(),
                        site.createdAt(), site.createdBy(), LocalDateTime.now(), updatedBy)))
                .then();
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

    private EppParameterResponse toResponse(Long siteId, List<EppParameter> validEpps, int cooldownSeconds) {
        return new EppParameterResponse(siteId,
                validEpps.stream().map(EppParameterResponse.EppItem::from).toList(), cooldownSeconds);
    }

    private void publishRules(EppParameterResponse response) {
        rulesPublisher.publishRules(response.siteId(),
                response.requiredEpp().stream().map(EppParameterResponse.EppItem::code).toList(),
                response.cooldownSeconds());
    }
}
