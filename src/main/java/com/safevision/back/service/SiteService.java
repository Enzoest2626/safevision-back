package com.safevision.back.service;

import com.safevision.back.dto.SiteRequest;
import com.safevision.back.dto.SiteResponse;
import com.safevision.back.model.Site;
import com.safevision.back.repository.SiteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class SiteService {

    private final SiteRepository siteRepository;

    public SiteService(SiteRepository siteRepository) {
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
        Site site = new Site(null, request.name(), request.location(), true, now, createdBy, now, createdBy);
        return siteRepository.save(site).map(SiteResponse::from);
    }

    public Mono<SiteResponse> update(Long id, SiteRequest request, String updatedBy) {
        return siteRepository.findById(id)
                .filter(Site::active)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found")))
                .flatMap(existing -> {
                    Site updated = new Site(
                            existing.id(), request.name(), request.location(), true,
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
                            existing.id(), existing.name(), existing.location(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return siteRepository.save(deactivated);
                })
                .then();
    }
}
