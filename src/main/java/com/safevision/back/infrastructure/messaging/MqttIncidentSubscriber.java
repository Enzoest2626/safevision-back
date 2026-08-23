package com.safevision.back.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safevision.back.application.service.IncidentService;
import com.safevision.back.infrastructure.messaging.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.messaging.dto.CvIncidentMessage;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Se suscribe a los incidentes y clips que publica el módulo CV por MQTT
 * (wildcard {@code +} cubre todas las obras en una sola suscripción — el
 * payload ya trae {@code site_name}, no hace falta parsear el topic).
 *
 * Sin caller HTTP al que devolverle un error: cualquier fallo (JSON
 * inválido, worker/cámara/obra desconocidos) se loguea y se descarta —
 * mismo criterio best-effort que el resto de integraciones externas.
 */
@Service
public class MqttIncidentSubscriber {

    private static final Logger log = LoggerFactory.getLogger(MqttIncidentSubscriber.class);

    private static final String INCIDENTS_TOPIC = "safevision/+/incidents";
    private static final String CLIPS_TOPIC = "safevision/+/incidents/clips";
    private static final int QOS = 1;

    private final MqttClient mqttClient;
    private final IncidentService incidentService;
    private final ObjectMapper objectMapper;

    public MqttIncidentSubscriber(MqttClient mqttClient, IncidentService incidentService,
                                   ObjectMapper objectMapper) {
        this.mqttClient = mqttClient;
        this.incidentService = incidentService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    log.info("Reconectado al broker MQTT — re-suscribiendo a incidentes/clips");
                    subscribeToTopics();
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("Conexion MQTT perdida | {}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                log.warn("Mensaje MQTT en topic sin listener especifico | topic={}", topic);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // No aplica: este cliente no publica desde esta clase.
            }
        });
        subscribeToTopics();
    }

    private void subscribeToTopics() {
        try {
            mqttClient.subscribe(INCIDENTS_TOPIC, QOS, (topic, message) -> handleIncident(message));
            mqttClient.subscribe(CLIPS_TOPIC, QOS, (topic, message) -> handleClipReady(message));
            log.info("Suscrito a MQTT | {} | {}", INCIDENTS_TOPIC, CLIPS_TOPIC);
        } catch (MqttException ex) {
            log.error("No se pudo suscribir a los topics de incidentes/clips | {}", ex.getMessage());
        }
    }

    private void handleIncident(MqttMessage message) {
        try {
            CvIncidentMessage payload = objectMapper.readValue(message.getPayload(), CvIncidentMessage.class);
            String traceId = UUID.randomUUID().toString();
            incidentService.registerFromCv(payload, traceId)
                    .doOnError(ex -> log.error("Fallo registrando incidente MQTT | incident_id={} | {}",
                            payload.incidentId(), ex.getMessage()))
                    .onErrorResume(ex -> Mono.empty())
                    .subscribe();
        } catch (Exception ex) {
            log.error("Mensaje MQTT de incidente invalido, se descarta | {}", ex.getMessage());
        }
    }

    private void handleClipReady(MqttMessage message) {
        try {
            CvClipReadyMessage payload = objectMapper.readValue(message.getPayload(), CvClipReadyMessage.class);
            incidentService.registerClipReady(payload)
                    .doOnError(ex -> log.error("Fallo registrando clip MQTT | incident_id={} | {}",
                            payload.incidentId(), ex.getMessage()))
                    .onErrorResume(ex -> Mono.empty())
                    .subscribe();
        } catch (Exception ex) {
            log.error("Mensaje MQTT de clip invalido, se descarta | {}", ex.getMessage());
        }
    }
}
