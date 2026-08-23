package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.SiteContact;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

@NoRepositoryBean
public interface SiteContactRepositoryPort extends ReactiveCrudRepository<SiteContact, Long> {

    Flux<SiteContact> findBySiteIdAndActiveTrue(Long siteId);

    /** Contactos con Telegram configurado — usados por el adaptador de alertas. */
    Flux<SiteContact> findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(Long siteId);
}
