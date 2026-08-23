package com.safevision.back.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bucket S3 donde el módulo CV sube evidencia (foto/clip); el backend solo
 * lee (URLs prefirmadas), nunca escribe.
 */
@ConfigurationProperties(prefix = "s3")
public record S3Properties(String bucket, String region, long presignTtlMinutes) {}
