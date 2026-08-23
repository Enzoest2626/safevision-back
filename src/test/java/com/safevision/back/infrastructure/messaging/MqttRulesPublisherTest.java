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

import java.util.List;
import java.util.Map;

@DisplayName("MqttRulesPublisher — publicacion retained de reglas EPP (HU04)")
class MqttRulesPublisherTest {

    @Test
    @DisplayName("publishRules publica retained en safevision/{siteCode}/rules con el payload correcto")
    void publishRules_publicaRetainedConPayloadCorrecto() throws Exception {
        MqttClient mqttClient = mock(MqttClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MqttRulesPublisher publisher = new MqttRulesPublisher(mqttClient, objectMapper);

        publisher.publishRules("OBRA-1", List.of("casco", "chaleco"));

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mqttClient).publish(eq("safevision/OBRA-1/rules"), messageCaptor.capture());
        MqttMessage message = messageCaptor.getValue();
        assertThat(message.isRetained()).isTrue();
        assertThat(message.getQos()).isEqualTo(1);

        Map<?, ?> payload = objectMapper.readValue(message.getPayload(), Map.class);
        assertThat(payload.get("required_epp")).isEqualTo(List.of("casco", "chaleco"));
    }

    @Test
    @DisplayName("Fallo al publicar (broker desconectado) no propaga excepcion")
    void publishRules_fallaAlPublicar_noPropagaExcepcion() throws Exception {
        MqttClient mqttClient = mock(MqttClient.class);
        org.mockito.Mockito.doThrow(new MqttException(32104)).when(mqttClient).publish(any(), any(MqttMessage.class));
        MqttRulesPublisher publisher = new MqttRulesPublisher(mqttClient, new ObjectMapper());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                publisher.publishRules("OBRA-1", List.of("casco")))).isNull();
    }
}
