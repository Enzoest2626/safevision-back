package com.safevision.back.service;

import com.safevision.back.dto.EppParameterRequest;
import com.safevision.back.dto.EppParameterResponse;
import com.safevision.back.model.SiteEppRequirement;
import com.safevision.back.repository.EppParameterRepository;
import com.safevision.back.repository.SiteEppRequirementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EppParameterService {

    private final EppParameterRepository eppRepo;
    private final SiteEppRequirementRepository siteEppRepo;

    public EppParameterService(EppParameterRepository eppRepo,
                               SiteEppRequirementRepository siteEppRepo) {
        this.eppRepo = eppRepo;
        this.siteEppRepo = siteEppRepo;
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

            // 1. Validar que todos los codes existen en el catálogo
            return Flux.fromIterable(request.requiredEpp())
                    .flatMap(code -> eppRepo.findByCode(code)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "EPP desconocido: " + code))))
                    .collectList()
                    .flatMap(validEpps -> {
                        // 2. Borrar configuración anterior de la obra
                        return siteEppRepo.deleteAllBySiteId(siteId)
                                .thenReturn(validEpps);
                    })
                    .flatMap(validEpps -> {
                        // 3. Insertar nueva configuración
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
                    ));
        });
    }
}
