package com.safevision.back.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * URL del webhook de recarga de parámetros expuesto por el módulo CV
 * (HU04). Vacía por defecto — el push queda deshabilitado y el CV sigue
 * sincronizando vía su polling periódico de 60s.
 */
@ConfigurationProperties(prefix = "cv")
public record CvProperties(String reloadUrl) {}
