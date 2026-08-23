package com.safevision.back.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

@DisplayName("MqttCameraConfigPublisher — publicacion retained de config de camara")
class MqttCameraConfigPublisherTest {

    @Test
    @DisplayName("publishCameraConfig publica retained en safevision/{siteCode}/{zoneCode}/{code}/config")
    void publishCameraConfig_publicaRetainedConPayloadCorrecto() throws Exception {
        MqttClient mqttClient = mock(MqttClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MqttCameraConfigPublisher publisher = new MqttCameraConfigPublisher(mqttClient, objectMapper);

        publisher.publishCameraConfig("OBRA-1", "ZONA-1", "CAM-01", "rtsp://10.0.0.5:554/stream", true);

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient).publish(eq("safevision/OBRA-1/ZONA-1/CAM-01/config"), messageCaptor.capture());
        MqttMessage message = messageCaptor.getValue();
        assertThat(message.isRetained()).isTrue();
        assertThat(message.getQos()).isEqualTo(1);

        Map<?, ?> payload = objectMapper.readValue(message.getPayload(), Map.class);
        assertThat(payload.get("rtsp_url")).isEqualTo("rtsp://10.0.0.5:554/stream");
        assertThat(payload.get("active")).isEqualTo(true);
    }

    @Test
    @DisplayName("Cámara desactivada publica active=false")
    void publishCameraConfig_camaraDesactivada_publicaActiveFalse() throws Exception {
        MqttClient mqttClient = mock(MqttClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MqttCameraConfigPublisher publisher = new MqttCameraConfigPublisher(mqttClient, objectMapper);

        publisher.publishCameraConfig("OBRA-1", "ZONA-1", "CAM-01", "rtsp://10.0.0.5:554/stream", false);

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient).publish(eq("safevision/OBRA-1/ZONA-1/CAM-01/config"), messageCaptor.capture());

        Map<?, ?> payload = objectMapper.readValue(messageCaptor.getValue().getPayload(), Map.class);
        assertThat(payload.get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("Fallo al publicar (broker desconectado) no propaga excepcion")
    void publishCameraConfig_fallaAlPublicar_noPropagaExcepcion() throws Exception {
        MqttClient mqttClient = mock(MqttClient.class);
        org.mockito.Mockito.doThrow(new MqttException(32104)).when(mqttClient).publish(any(), any(MqttMessage.class));
        MqttCameraConfigPublisher publisher = new MqttCameraConfigPublisher(mqttClient, new ObjectMapper());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                publisher.publishCameraConfig("OBRA-1", "ZONA-1", "CAM-01", "rtsp://10.0.0.5:554/stream", true)))
                .isNull();
    }
}
