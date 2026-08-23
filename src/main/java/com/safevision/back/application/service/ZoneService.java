package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.ZoneRepositoryPort;
import com.safevision.back.domain.model.Zone;
import com.safevision.back.infrastructure.web.dto.ZoneRequest;
import com.safevision.back.infrastructure.web.dto.ZoneResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class ZoneService {

    private final ZoneRepositoryPort repository;

    public ZoneService(ZoneRepositoryPort repository) {
        this.repository = repository;
    }

    public Flux<ZoneResponse> findBySite(Long siteId) {
        return repository.findBySiteIdAndActiveTrue(siteId).map(ZoneResponse::from);
    }

    public Mono<ZoneResponse> create(Long siteId, ZoneRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        return resolveCode(siteId, request.code())
                .flatMap(code -> {
                    Zone zone = new Zone(null, siteId, code, request.name(), true, now, createdBy, now, createdBy);
                    return repository.save(zone);
                })
                .map(ZoneResponse::from);
    }

    public Mono<ZoneResponse> update(Long siteId, Long zoneId, ZoneRequest request, String updatedBy) {
        return repository.findById(zoneId)
                .filter(z -> z.active() && z.siteId().equals(siteId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone not found")))
                .flatMap(existing -> {
                    Zone updated = new Zone(
                            existing.id(), siteId, existing.code(), request.name(), true,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(updated);
                })
                .map(ZoneResponse::from);
    }

    public Mono<Void> delete(Long siteId, Long zoneId, String updatedBy) {
        return repository.findById(zoneId)
                .filter(z -> z.active() && z.siteId().equals(siteId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone not found")))
                .flatMap(existing -> {
                    Zone deactivated = new Zone(
                            existing.id(), siteId, existing.code(), existing.name(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(deactivated);
                })
                .then();
    }

    /**
     * Autogenera el código si el cliente no especificó uno; si lo especificó,
     * valida que no exista ya para esta obra (único por obra, no global —
     * dos obras distintas pueden repetir código de zona sin chocar).
     */
    private Mono<String> resolveCode(Long siteId, String requestedCode) {
        if (requestedCode == null || requestedCode.isBlank()) {
            return Mono.just(CodeGenerator.generate("ZONE"));
        }
        String trimmed = requestedCode.trim();
        return repository.existsBySiteIdAndCode(siteId, trimmed)
                .flatMap(exists -> exists
                        ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Ya existe una zona con el código '" + trimmed + "' en esta obra"))
                        : Mono.just(trimmed));
    }
}
