package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.SiteContact;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface SiteContactRepositoryPort extends ReactiveCrudRepository<SiteContact, Long> {

    Flux<SiteContact> findBySiteIdAndActiveTrue(Long siteId);

    /** Contactos con Telegram configurado — usados por el adaptador de alertas. */
    Flux<SiteContact> findBySiteIdAndTelegramChatIdIsNotNullAndActiveTrue(Long siteId);

    /** Usado por {@code TelegramLinkingPoller} para resolver el código que manda el supervisor por el bot. */
    Mono<SiteContact> findByTelegramLinkCodeAndActiveTrue(String telegramLinkCode);
}
