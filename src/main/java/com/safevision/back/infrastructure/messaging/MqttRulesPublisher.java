package com.safevision.back.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safevision.back.application.ports.out.RulesPublisherPort;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Publica (retained) las reglas EPP de una obra en {@code safevision/{siteCode}/rules}
 * cuando cambian (HU04). Reemplaza el webhook HTTP que antes le pegaba al CV
 * ({@code CvNotificationService}) — ahora el CV solo escucha, no consulta.
 *
 * Un mensaje retained vive en el broker independientemente del uptime del
 * backend: no hace falta volver a publicar al reiniciar, el último PUT
 * sigue disponible para cualquier CV que se suscriba después.
 */
@Service
public class MqttRulesPublisher implements RulesPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(MqttRulesPublisher.class);

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MqttRulesPublisher(MqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishRules(String siteCode, List<String> requiredEppCodes) {
        String topic = "safevision/" + siteCode + "/rules";
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of("required_epp", requiredEppCodes));
            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            message.setRetained(true);
            mqttClient.publish(topic, message);
            log.info("Reglas EPP publicadas por MQTT | topic={} | required_epp={}", topic, requiredEppCodes);
        } catch (Exception ex) {
            log.error("Fallo publicando reglas EPP por MQTT | site_code={} | {}", siteCode, ex.getMessage());
        }
    }
}
