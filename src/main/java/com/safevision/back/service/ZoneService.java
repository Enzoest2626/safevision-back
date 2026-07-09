package com.safevision.back.service;

import com.safevision.back.dto.ZoneRequest;
import com.safevision.back.dto.ZoneResponse;
import com.safevision.back.model.Zone;
import com.safevision.back.repository.ZoneRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class ZoneService {

    private final ZoneRepository repository;

    public ZoneService(ZoneRepository repository) {
        this.repository = repository;
    }

    public Flux<ZoneResponse> findBySite(Long siteId) {
        return repository.findBySiteIdAndActiveTrue(siteId).map(ZoneResponse::from);
    }

    public Mono<ZoneResponse> create(Long siteId, ZoneRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        Zone zone = new Zone(null, siteId, request.name(), true, now, createdBy, now, createdBy);
        return repository.save(zone).map(ZoneResponse::from);
    }

    public Mono<ZoneResponse> update(Long siteId, Long zoneId, ZoneRequest request, String updatedBy) {
        return repository.findById(zoneId)
                .filter(z -> z.active() && z.siteId().equals(siteId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone not found")))
                .flatMap(existing -> {
                    Zone updated = new Zone(
                            existing.id(), siteId, request.name(), true,
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
                            existing.id(), siteId, existing.name(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(deactivated);
                })
                .then();
    }
}
