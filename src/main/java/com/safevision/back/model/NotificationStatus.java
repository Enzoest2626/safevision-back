package com.safevision.back.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("notification_statuses")
public record NotificationStatus(@Id Long id, String code, String name) {}
