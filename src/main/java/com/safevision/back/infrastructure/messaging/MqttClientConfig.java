package com.safevision.back.infrastructure.messaging;

import com.safevision.back.infrastructure.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cliente MQTT único y compartido por todo el proceso — lo usan tanto
 * {@code MqttIncidentSubscriber} (suscripción a incidentes/clips del CV)
 * como {@code MqttRulesPublisher} (publicación retained de reglas EPP).
 *
 * Si {@code MQTT_BROKER_HOST} no está configurado, o la conexión falla al
 * arrancar (broker caído), se loguea y la app sigue levantando igual —
 * mismo criterio best-effort que el resto de integraciones externas de
 * este backend (Telegram, ex-CvNotificationService).
 */
@Configuration
public class MqttClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttClientConfig.class);

    @Bean
    MqttClient mqttClient(MqttProperties properties) throws MqttException {
        String clientId = (properties.clientId() != null && !properties.clientId().isBlank())
                ? properties.clientId()
                : "safevision-back-" + System.currentTimeMillis();
        String brokerHost = properties.brokerHost();

        if (brokerHost == null || brokerHost.isBlank()) {
            log.warn("MQTT_BROKER_HOST no configurado — incidentes/clips/reglas via MQTT deshabilitados");
            return new MqttClient("tcp://localhost:1883", clientId, new MemoryPersistence());
        }

        MqttClient client = new MqttClient(
                "tcp://" + brokerHost + ":" + properties.brokerPort(), clientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        if (properties.username() != null && !properties.username().isBlank()) {
            options.setUserName(properties.username());
            options.setPassword(properties.password() != null ? properties.password().toCharArray() : new char[0]);
        }

        try {
            client.connect(options);
            log.info("Conectado al broker MQTT | {}:{}", brokerHost, properties.brokerPort());
        } catch (MqttException ex) {
            log.error("No se pudo conectar al broker MQTT | {}:{} | {}", brokerHost, properties.brokerPort(),
                    ex.getMessage());
        }
        return client;
    }
}
