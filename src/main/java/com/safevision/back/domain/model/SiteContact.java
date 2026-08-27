package com.safevision.back.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("site_contacts")
public record SiteContact(
        @Id Long id,
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
) {}
