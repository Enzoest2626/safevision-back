package com.safevision.back.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hikvision")
public record HikvisionProperties(String baseUrl, String username, String password) {}
