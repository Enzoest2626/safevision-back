package com.safevision.back.infrastructure.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.CameraRepositoryPort;
import com.safevision.back.domain.model.Camera;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unitario (sin red real) — mockea el ExchangeFunction del WebClient para
 * verificar el fan-out por cámara sin depender de que el CV esté arriba.
 */
@DisplayName("HttpRulesPublisher — notificación por HTTP de reglas EPP (HU04)")
class HttpRulesPublisherTest {

    private static final Long SITE_ID = 1L;

    private final Camera camaraConIp = new Camera(1L, SITE_ID, 20L, "CAM-01", "Entrada",
            "10.0.0.5", null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    private final Camera camaraSinIp = new Camera(2L, SITE_ID, 20L, "CAM-02", "Patio",
            null, null, true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");

    private HttpRulesPublisher buildPublisher(CameraRepositoryPort cameraRepository,
                                               ExchangeFunction exchangeFunction) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new HttpRulesPublisher(builder, cameraRepository, 5001);
    }

    @Test
    @DisplayName("publishRules hace POST a cada cámara activa de la obra con ip_address")
    void publishRules_haceFanOutPorCamaraConIp() {
        CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
        when(cameraRepository.findBySiteIdAndActiveTrue(SITE_ID))
                .thenReturn(Flux.just(camaraConIp, camaraSinIp));

        List<ClientRequest> capturedRequests = new CopyOnWriteArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        HttpRulesPublisher publisher = buildPublisher(cameraRepository, exchangeFunction);

        publisher.publishRules(SITE_ID, List.of("casco", "chaleco"));

        assertThat(capturedRequests).hasSize(1);
        assertThat(capturedRequests.get(0).method().name()).isEqualTo("POST");
        assertThat(capturedRequests.get(0).url().toString()).isEqualTo("http://10.0.0.5:5001/webhook/rules");
    }

    @Test
    @DisplayName("Fallo al notificar una cámara (CV apagado/inalcanzable) no propaga excepción")
    void publishRules_fallaAlNotificar_noPropagaExcepcion() {
        CameraRepositoryPort cameraRepository = mock(CameraRepositoryPort.class);
        when(cameraRepository.findBySiteIdAndActiveTrue(SITE_ID)).thenReturn(Flux.just(camaraConIp));
        ExchangeFunction exchangeFunction = request -> Mono.error(new RuntimeException("Connection refused"));
        HttpRulesPublisher publisher = buildPublisher(cameraRepository, exchangeFunction);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                publisher.publishRules(SITE_ID, List.of("casco")))).isNull();
    }
}
