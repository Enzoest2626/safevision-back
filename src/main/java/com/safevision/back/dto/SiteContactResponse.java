package com.safevision.back.dto;

import com.safevision.back.model.SiteContact;

import java.time.LocalDateTime;

public record SiteContactResponse(
        Long id,
        Long siteId,
        String name,
        String phone,
        String telegramChatId,
        boolean active,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy
) {
    public static SiteContactResponse from(SiteContact c) {
        return new SiteContactResponse(
                c.id(), c.siteId(), c.name(), c.phone(), c.telegramChatId(),
                c.active(), c.createdAt(), c.createdBy(), c.updatedAt(), c.updatedBy()
        );
    }
}
