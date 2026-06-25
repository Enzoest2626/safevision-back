package com.safevision.back.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("notifications")
public record Notification(
        @Id Long id,
        Long incidentId,
        Long channelId,
        Long statusId,
        LocalDateTime sentAt,
        String errorMsg,
        LocalDateTime createdAt
) {}
