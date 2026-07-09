package com.safevision.back.repository;

import com.safevision.back.model.SiteContact;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface SiteContactRepository extends ReactiveCrudRepository<SiteContact, Long> {

    Flux<SiteContact> findBySiteIdAndActiveTrue(Long siteId);

    /** Contactos con Telegram configurado — usados por el adaptador de alertas. */
    Flux<SiteContact> findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(Long siteId);
}
