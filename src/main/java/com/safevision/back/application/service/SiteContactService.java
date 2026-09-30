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

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class SiteContactService {

    private static final SecureRandom LINK_CODE_RANDOM = new SecureRandom();

    private final SiteContactRepositoryPort repository;

    public SiteContactService(SiteContactRepositoryPort repository) {
        this.repository = repository;
    }

    /** Todos los contactos de la obra, activos e inactivos — la pantalla de
     * gestión necesita ver (y poder reactivar) a los que están en pausa. */
    public Flux<SiteContactResponse> findBySite(Long siteId) {
        return repository.findBySiteId(siteId)
                .map(SiteContactResponse::from);
    }

    public Mono<SiteContactResponse> create(Long siteId, SiteContactRequest request, String createdBy) {
        LocalDateTime now = LocalDateTime.now();
        String linkCode = request.telegramChatId() == null ? generateLinkCode() : null;
        SiteContact contact = new SiteContact(
                null, siteId, request.name(), request.phone(), request.telegramChatId(), linkCode,
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
                    // Si el admin pega un chat_id a mano, ya no hace falta código de
                    // vinculación. Si sigue sin chat_id, conserva el código pendiente
                    // que ya tenía (o genera uno si el contacto es de antes de esta
                    // funcionalidad y nunca tuvo).
                    String linkCode = request.telegramChatId() != null
                            ? null
                            : existing.telegramLinkCode() != null ? existing.telegramLinkCode() : generateLinkCode();
                    SiteContact updated = new SiteContact(
                            existing.id(), siteId, request.name(), request.phone(),
                            request.telegramChatId(), linkCode, true,
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
                            existing.telegramChatId(), existing.telegramLinkCode(), false,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(deactivated);
                })
                .then();
    }

    /**
     * Pausa o reanuda las alertas de un contacto sin tocar su vínculo de
     * Telegram (chat_id/código pendiente quedan intactos) — a diferencia de
     * {@link #delete}, funciona en ambos sentidos: sirve para reactivar a
     * alguien que estaba desactivado (ej. volvió de vacaciones).
     */
    public Mono<SiteContactResponse> setActive(Long siteId, Long contactId, boolean active, String updatedBy) {
        return repository.findById(contactId)
                .filter(c -> c.siteId().equals(siteId))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found")))
                .flatMap(existing -> {
                    SiteContact updated = new SiteContact(
                            existing.id(), siteId, existing.name(), existing.phone(),
                            existing.telegramChatId(), existing.telegramLinkCode(), active,
                            existing.createdAt(), existing.createdBy(),
                            LocalDateTime.now(), updatedBy
                    );
                    return repository.save(updated);
                })
                .map(SiteContactResponse::from);
    }

    /**
     * Intenta vincular un contacto pendiente a partir del texto recibido por el
     * bot de Telegram. Usado por {@code TelegramLinkingPoller}.
     *
     * @return el nombre del contacto vinculado, o vacío si el texto no matchea
     *         ningún código pendiente.
     */
    public Mono<String> tryLinkByCode(String text, String chatId) {
        return repository.findByTelegramLinkCodeAndActiveTrue(text.trim())
                .flatMap(contact -> {
                    SiteContact linked = new SiteContact(
                            contact.id(), contact.siteId(), contact.name(), contact.phone(),
                            chatId, null, true,
                            contact.createdAt(), contact.createdBy(),
                            LocalDateTime.now(), "telegram-bot"
                    );
                    return repository.save(linked);
                })
                .map(SiteContact::name);
    }

    private String generateLinkCode() {
        return String.format("%06d", LINK_CODE_RANDOM.nextInt(1_000_000));
    }
}
