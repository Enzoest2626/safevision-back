package com.safevision.back.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safevision.back.application.ports.out.CameraConfigPublisherPort;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Publica (retained) la configuración de una cámara en
 * {@code safevision/{siteCode}/{zoneCode}/{cameraCode}/config} cada vez que
 * cambia (creación, actualización, desactivación) — mismo patrón que
 * {@link MqttRulesPublisher} para reglas EPP (HU04): el CV solo escucha,
 * nunca consulta.
 *
 * Un mensaje retained vive en el broker independientemente del uptime del
 * backend: no hace falta volver a publicar al reiniciar, la última
 * configuración sigue disponible para cualquier CV que se suscriba después.
 */
@Service
public class MqttCameraConfigPublisher implements CameraConfigPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(MqttCameraConfigPublisher.class);

    private final MqttClient mqttClient;
    private final ObjectMapper objectMapper;

    public MqttCameraConfigPublisher(MqttClient mqttClient, ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishCameraConfig(String siteCode, String zoneCode, String cameraCode, String rtspUrl,
                                     boolean active) {
        String topic = "safevision/" + siteCode + "/" + zoneCode + "/" + cameraCode + "/config";
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("rtsp_url", rtspUrl);
            payload.put("active", active);
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            MqttMessage message = new MqttMessage(bytes);
            message.setQos(1);
            message.setRetained(true);
            mqttClient.publish(topic, message);
            log.info(
                    "Config de cámara publicada por MQTT | topic={} | rtsp_url={} | active={}",
                    topic, rtspUrl, active);
        } catch (Exception ex) {
            log.error(
                    "Fallo publicando config de cámara por MQTT | site_code={} | zone_code={} | camera_code={} | {}",
                    siteCode, zoneCode, cameraCode, ex.getMessage());
        }
    }
}
