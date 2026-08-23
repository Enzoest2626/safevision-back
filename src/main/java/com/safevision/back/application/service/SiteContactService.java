package com.safevision.back.application.service;

import com.safevision.back.application.ports.out.SiteContactRepositoryPort;
import com.safevision.back.domain.model.SiteContact;
import com.safevision.back.infrastructure.web.dto.SiteContactRequest;
import com.safevision.back.infrastructure.web.dto.SiteContactResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class SiteContactService {

    private final SiteContactRepositoryPort repository;

    public SiteContactService(SiteContactRepositoryPort repository) {
        this.repository = repository;
    }

    public Flux<SiteContactResponse> findBySite(Long siteId) {
        return repository.findBySiteIdAndActiveTrue(siteId)
                .map(SiteContactResponse::from);
    }

    public Mono<SiteContactResponse> create(Long siteId, SiteContactRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        SiteContact contact = new SiteContact(
                null, siteId, request.name(), request.phone(), request.telegramChatId(),
                true, now, createdBy, now, createdBy
        );
        return repository.save(contact).map(SiteContactResponse::from);
    }

    public Mono<SiteContactResponse> update(Long siteId, Long contactId,
                                             SiteContactRequest request, String updatedBy) {
        return repository.findById(contactId)
                .filter(c -> c.active() && c.siteId().equals(siteId))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found")))
                .flatMap(existing -> {
                    SiteContact updated = new SiteContact(
                            existing.id(), siteId, request.name(), request.phone(),
                            request.telegramChatId(), true,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(updated);
                })
                .map(SiteContactResponse::from);
    }

    public Mono<Void> delete(Long siteId, Long contactId, String updatedBy) {
        return repository.findById(contactId)
                .filter(c -> c.active() && c.siteId().equals(siteId))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found")))
                .flatMap(existing -> {
                    SiteContact deactivated = new SiteContact(
                            existing.id(), siteId, existing.name(), existing.phone(),
                            existing.telegramChatId(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(deactivated);
                })
                .then();
    }
}
