package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.EppParameterRepositoryPort;
import com.safevision.back.application.ports.out.RulesPublisherPort;
import com.safevision.back.application.ports.out.SiteEppConfigVersionRepositoryPort;
import com.safevision.back.application.ports.out.SiteEppRequirementRepositoryPort;
import com.safevision.back.application.ports.out.SiteRepositoryPort;
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

    private final EppParameterRepositoryPort eppRepo;
    private final SiteEppRequirementRepositoryPort siteEppRepo;
    private final SiteEppConfigVersionRepositoryPort versionRepo;
    private final RulesPublisherPort mqttRulesPublisher;
    private final SiteRepositoryPort siteRepository;

    public EppParameterService(EppParameterRepositoryPort eppRepo,
                               SiteEppRequirementRepositoryPort siteEppRepo,
                               SiteEppConfigVersionRepositoryPort versionRepo,
                               RulesPublisherPort mqttRulesPublisher,
                               SiteRepositoryPort siteRepository) {
        this.eppRepo = eppRepo;
        this.siteEppRepo = siteEppRepo;
        this.versionRepo = versionRepo;
        this.mqttRulesPublisher = mqttRulesPublisher;
        this.siteRepository = siteRepository;
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
        return Mono.defer(() -> {
            if (request.requiredEpp() == null || request.requiredEpp().isEmpty()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "requiredEpp no puede estar vacío"));
            }

            // 1. Validar que todos los codes existen en el catálogo (sin efectos secundarios)
            return Flux.fromIterable(request.requiredEpp())
                    .flatMap(code -> eppRepo.findByCode(code)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "EPP desconocido: " + code))))
                    .collectList()
                    .flatMap(validEpps ->
                            // 2. CAS de versión (CP18) — si otra solicitud ya modificó esta obra
                            // entre que leímos la versión y este UPDATE, compareAndSwap afecta 0
                            // filas y switchIfEmpty dispara 409 sin tocar site_epp_requirements.
                            versionRepo.ensureExists(siteId)
                                    .then(versionRepo.findById(siteId))
                                    .flatMap(v -> versionRepo.compareAndSwap(siteId, v.version()))
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "La configuración EPP de esta obra fue modificada por otra solicitud; reintente.")))
                                    .thenReturn(validEpps)
                    )
                    .flatMap(validEpps -> {
                        // 3. Borrar configuración anterior de la obra
                        return siteEppRepo.deleteAllBySiteId(siteId)
                                .thenReturn(validEpps);
                    })
                    .flatMap(validEpps -> {
                        // 4. Insertar nueva configuración
                        LocalDateTime now = LocalDateTime.now();
                        List<SiteEppRequirement> newReqs = validEpps.stream()
                                .map(epp -> new SiteEppRequirement(
                                        null, siteId, epp.id(), now, updatedBy))
                                .toList();
                        return Flux.fromIterable(newReqs)
                                .flatMap(siteEppRepo::save)
                                .then(Mono.just(validEpps));
                    })
                    .map(validEpps -> new EppParameterResponse(
                            siteId,
                            validEpps.stream()
                                    .map(EppParameterResponse.EppItem::from)
                                    .toList()
                    ))
                    .flatMap(response -> siteRepository.findById(siteId)
                            .map(Site::code)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "Site not found")))
                            .doOnNext(siteCode -> mqttRulesPublisher.publishRules(siteCode,
                                    response.requiredEpp().stream().map(EppParameterResponse.EppItem::code).toList()))
                            .thenReturn(response));
        });
    }
}
