package com.safevision.back.infrastructure.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.Camera;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unitario (sin red real) — mockea el ExchangeFunction del WebClient para
 * verificar la URI/payload del webhook sin depender de que el CV esté arriba.
 */
@DisplayName("HttpCameraConfigPublisher — notificación por HTTP de config de cámara")
class HttpCameraConfigPublisherTest {

    private final Camera camera = new Camera(1L, 10L, 20L, "CAM-01", "Entrada",
            "10.0.0.5", "rtsp://10.0.0.5:554/stream", true,
            LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private HttpCameraConfigPublisher buildPublisher(ExchangeFunction exchangeFunction) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new HttpCameraConfigPublisher(builder, 5001);
    }

    @Test
    @DisplayName("publishCameraConfig hace POST a http://{ip}:5001/webhook/config con el payload correcto")
    void publishCameraConfig_haceHttpPostConPayloadCorrecto() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        HttpCameraConfigPublisher publisher = buildPublisher(exchangeFunction);

        publisher.publishCameraConfig(camera);

        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().method().name()).isEqualTo("POST");
        assertThat(capturedRequest.get().url().toString()).isEqualTo("http://10.0.0.5:5001/webhook/config");
    }

    @Test
    @DisplayName("Cámara sin ip_address no hace ningún llamado")
    void publishCameraConfig_sinIpAddress_noLlama() {
        Camera sinIp = new Camera(1L, 10L, 20L, "CAM-01", "Entrada", null, null, true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        HttpCameraConfigPublisher publisher = buildPublisher(exchangeFunction);

        publisher.publishCameraConfig(sinIp);

        assertThat(capturedRequest.get()).isNull();
    }

    @Test
    @DisplayName("Fallo al notificar (CV apagado/inalcanzable) no propaga excepción")
    void publishCameraConfig_fallaAlNotificar_noPropagaExcepcion() {
        ExchangeFunction exchangeFunction = request -> Mono.error(new RuntimeException("Connection refused"));
        HttpCameraConfigPublisher publisher = buildPublisher(exchangeFunction);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                publisher.publishCameraConfig(camera))).isNull();
    }
}
