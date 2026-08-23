package com.safevision.back.infrastructure.messaging;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safevision.back.application.service.IncidentService;
import com.safevision.back.infrastructure.messaging.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.messaging.dto.CvIncidentMessage;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@DisplayName("MqttIncidentSubscriber — ingesta de incidentes/clips publicados por el CV")
class MqttIncidentSubscriberTest {

    private MqttClient mqttClient;
    private IncidentService incidentService;
    private MqttIncidentSubscriber subscriber;

    @BeforeEach
    void setUp() {
        mqttClient = mock(MqttClient.class);
        incidentService = mock(IncidentService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        subscriber = new MqttIncidentSubscriber(mqttClient, incidentService, objectMapper);
    }

    private IMqttMessageListener captureIncidentListener() throws Exception {
        subscriber.init();
        ArgumentCaptor<IMqttMessageListener> captor = ArgumentCaptor.forClass(IMqttMessageListener.class);
        verify(mqttClient).subscribe(eq("safevision/+/incidents"), anyInt(), captor.capture());
        return captor.getValue();
    }

    private IMqttMessageListener captureClipListener() throws Exception {
        subscriber.init();
        ArgumentCaptor<IMqttMessageListener> captor = ArgumentCaptor.forClass(IMqttMessageListener.class);
        verify(mqttClient).subscribe(eq("safevision/+/incidents/clips"), anyInt(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("init() se suscribe a los topics de incidentes y clips")
    void init_seSuscribeAAmbosTopics() throws Exception {
        subscriber.init();

        verify(mqttClient).subscribe(eq("safevision/+/incidents"), anyInt(), any(IMqttMessageListener.class));
        verify(mqttClient).subscribe(eq("safevision/+/incidents/clips"), anyInt(), any(IMqttMessageListener.class));
    }

    @Test
    @DisplayName("Mensaje de incidente valido -> llama a incidentService.registerFromCv con los datos parseados")
    void mensajeIncidenteValido_llamaRegisterFromCv() throws Exception {
        when(incidentService.registerFromCv(any(CvIncidentMessage.class), anyString()))
                .thenReturn(Mono.just(new IncidentResponse(100L, 10L, 20L, 1L,
                        List.of("helmet"), LocalDateTime.now(), LocalDateTime.now())));
        IMqttMessageListener listener = captureIncidentListener();

        String json = """
                {"incident_id":"cv-uuid-1","worker_code":3,"missing_epp":["helmet"],
                 "timestamp":"2026-06-24T13:30:00","camera_code":"CAM-01","site_name":"Main-Site",
                 "photo_s3_key":"incidents/2026-08-10/cv-uuid-1/photo.jpg"}
                """;
        listener.messageArrived("safevision/1/incidents", new MqttMessage(json.getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<CvIncidentMessage> captor = ArgumentCaptor.forClass(CvIncidentMessage.class);
        verify(incidentService).registerFromCv(captor.capture(), anyString());
        CvIncidentMessage parsed = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(parsed.incidentId()).isEqualTo("cv-uuid-1");
        org.assertj.core.api.Assertions.assertThat(parsed.workerCode()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(parsed.photoS3Key())
                .isEqualTo("incidents/2026-08-10/cv-uuid-1/photo.jpg");
    }

    @Test
    @DisplayName("Mensaje de incidente con JSON invalido -> no lanza, no llama al servicio")
    void mensajeIncidenteInvalido_noLanzaNiLlamaAlServicio() throws Exception {
        IMqttMessageListener listener = captureIncidentListener();

        org.assertj.core.api.Assertions.assertThatCode(() ->
                listener.messageArrived("safevision/1/incidents",
                        new MqttMessage("no-es-json".getBytes(StandardCharsets.UTF_8)))
        ).doesNotThrowAnyException();

        verify(incidentService, never()).registerFromCv(any(), anyString());
    }

    @Test
    @DisplayName("registerFromCv falla (worker desconocido) -> el error se loguea, no propaga")
    void registerFromCvFalla_noPropagaError() throws Exception {
        when(incidentService.registerFromCv(any(CvIncidentMessage.class), anyString()))
                .thenReturn(Mono.error(new IllegalArgumentException("Worker desconocido: 3")));
        IMqttMessageListener listener = captureIncidentListener();

        String json = """
                {"incident_id":"cv-uuid-1","worker_code":3,"missing_epp":["helmet"],
                 "timestamp":"2026-06-24T13:30:00","camera_code":"CAM-01","site_name":"Main-Site",
                 "photo_s3_key":"incidents/2026-08-10/cv-uuid-1/photo.jpg"}
                """;

        org.assertj.core.api.Assertions.assertThatCode(() ->
                listener.messageArrived("safevision/1/incidents", new MqttMessage(json.getBytes(StandardCharsets.UTF_8)))
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Mensaje de clip listo valido -> llama a incidentService.registerClipReady con los datos parseados")
    void mensajeClipValido_llamaRegisterClipReady() throws Exception {
        when(incidentService.registerClipReady(any(CvClipReadyMessage.class))).thenReturn(Mono.empty());
        IMqttMessageListener listener = captureClipListener();

        String json = """
                {"incident_id":"cv-uuid-1","s3_key":"incidents/2026-08-10/cv-uuid-1/clip.mp4",
                 "duration_seconds":10.0,"file_size_bytes":4831201}
                """;
        listener.messageArrived("safevision/1/incidents/clips", new MqttMessage(json.getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<CvClipReadyMessage> captor = ArgumentCaptor.forClass(CvClipReadyMessage.class);
        verify(incidentService).registerClipReady(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().incidentId()).isEqualTo("cv-uuid-1");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().s3Key())
                .isEqualTo("incidents/2026-08-10/cv-uuid-1/clip.mp4");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().fileSizeBytes()).isEqualTo(4831201L);
    }

    @Test
    @DisplayName("Reconexion (connectComplete reconnect=true) -> vuelve a suscribirse")
    void reconexion_vuelveASuscribirse() throws Exception {
        subscriber.init();
        ArgumentCaptor<MqttCallbackExtended> callbackCaptor = ArgumentCaptor.forClass(MqttCallbackExtended.class);
        verify(mqttClient).setCallback(callbackCaptor.capture());

        callbackCaptor.getValue().connectComplete(true, "tcp://broker:1883");

        verify(mqttClient, times(2)).subscribe(eq("safevision/+/incidents"), anyInt(), any(IMqttMessageListener.class));
    }

    @Test
    @DisplayName("connectComplete con reconnect=false (conexion inicial) -> no vuelve a suscribirse de mas")
    void conexionInicial_noResuscribeDeMas() throws Exception {
        subscriber.init();
        ArgumentCaptor<MqttCallbackExtended> callbackCaptor = ArgumentCaptor.forClass(MqttCallbackExtended.class);
        verify(mqttClient).setCallback(callbackCaptor.capture());

        callbackCaptor.getValue().connectComplete(false, "tcp://broker:1883");

        verify(mqttClient, times(1)).subscribe(eq("safevision/+/incidents"), anyInt(), any(IMqttMessageListener.class));
    }
}
