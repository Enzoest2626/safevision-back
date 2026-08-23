package com.safevision.back.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del broker MQTT compartido — usado para suscribirse a
 * incidentes/clips publicados por el módulo CV y para publicar (retained)
 * las reglas EPP de cada obra.
 */
@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(String brokerHost, int brokerPort, String username, String password,
                              String clientId) {}
