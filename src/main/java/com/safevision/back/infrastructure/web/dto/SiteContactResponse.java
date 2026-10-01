package com.safevision.back.infrastructure.web.dto;

import com.safevision.back.domain.model.SiteContact;

import java.time.LocalDateTime;

public record SiteContactResponse(
        Long id,
        Long siteId,
        String name,
        String phone,
        String telegramChatId,
        String telegramLinkCode,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static SiteContactResponse from(SiteContact c) {
        return new SiteContactResponse(
                c.id(), c.siteId(), c.name(), c.phone(), c.telegramChatId(), c.telegramLinkCode(),
                c.active(), c.createdAt(), c.createdBy(), c.updatedAt(), c.updatedBy()
        );
    }
}
