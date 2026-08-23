package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.domain.model.Site;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import com.safevision.back.infrastructure.web.dto.SiteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class SiteService {

    private final SiteRepositoryPort siteRepository;

    public SiteService(SiteRepositoryPort siteRepository) {
        this.siteRepository = siteRepository;
    }

    public Flux<SiteResponse> findAll() {
        return siteRepository.findByActiveTrue().map(SiteResponse::from);
    }

    public Mono<SiteResponse> findById(Long id) {
        return siteRepository.findById(id)
                .filter(Site::active)
                .map(SiteResponse::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")));
    }

    public Mono<SiteResponse> create(SiteRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        return resolveCode(request.code())
                .flatMap(code -> {
                    Site site = new Site(null, code, request.name(), request.location(), true, now, createdBy, now,
                            createdBy);
                    return siteRepository.save(site);
                })
                .map(SiteResponse::from);
    }

    public Mono<SiteResponse> update(Long id, SiteRequest request, String updatedBy) {
        return siteRepository.findById(id)
                .filter(Site::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")))
                .flatMap(existing -> {
                    Site updated = new Site(
                            existing.id(), existing.code(), request.name(), request.location(), true,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return siteRepository.save(updated);
                })
                .map(SiteResponse::from);
    }

    public Mono<Void> delete(Long id, String updatedBy) {
        return siteRepository.findById(id)
                .filter(Site::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")))
                .flatMap(existing -> {
                    Site deactivated = new Site(
                            existing.id(), existing.code(), existing.name(), existing.location(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return siteRepository.save(deactivated);
                })
                .then();
    }

    /**
     * Autogenera el código si el cliente no especificó uno; si lo especificó,
     * valida que no exista ya (409 en vez de dejar que la violación de
     * constraint única de la BD llegue cruda al cliente).
     */
    private Mono<String> resolveCode(String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return Mono.just(CodeGenerator.generate("SITE"));
        }
        String trimmed = requestedCode.trim();
        return siteRepository.existsByCode(trimmed)
                .flatMap(exists -> exists
                        ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Ya existe una obra con el código '" + trimmed + "'"))
                        : Mono.just(trimmed));
    }
}
