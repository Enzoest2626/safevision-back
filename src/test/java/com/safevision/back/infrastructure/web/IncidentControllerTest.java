package com.safevision.back.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.IncidentService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.CvClipReadyMessage;
import com.safevision.back.infrastructure.web.dto.CvIncidentMessage;
import com.safevision.back.infrastructure.web.dto.EvidenceResponse;
import com.safevision.back.infrastructure.web.dto.IncidentRequest;
import com.safevision.back.infrastructure.web.dto.IncidentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentController — HTTP (HU10)")
class IncidentControllerTest {

    @Mock
    private IncidentService service;

    private WebTestClient client;

    private IncidentResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new IncidentController(service)).build();
        sampleResponse = new IncidentResponse(100L, 10L, 20L, 1L,
                List.of("helmet", "vest"), LocalDateTime.of(2026, 6, 24, 13, 30), LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/v1/incidents con payload válido retorna 201")
    void register_retorna201() {
        when(service.register(any(IncidentRequest.class), any())).thenReturn(Mono.just(sampleResponse));

        IncidentRequest request = new IncidentRequest(3, List.of("helmet", "vest"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", "Main-Site", "ZmFrZS1mcmFtZQ==");

        client.post().uri("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<IncidentResponse>>() {});
    }

    @Test
    @DisplayName("POST /api/v1/incidents con worker desconocido retorna 400")
    void register_workerDesconocido_retorna400() {
        when(service.register(any(IncidentRequest.class), any())).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Worker desconocido: 3")));

        IncidentRequest request = new IncidentRequest(3, List.of("helmet"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", "Main-Site", "ZmFrZS1mcmFtZQ==");

        client.post().uri("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── CP31: Ausencia de notificación sin incumplimiento ────────────────────

    @Test
    @DisplayName("CP31 — Sin incumplimiento activo (missing_epp vacío) no se registra ni se notifica")
    void cp31_sinIncumplimientoActivo_noRegistraNiNotifica() {
        client.post().uri("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"worker_code":3,"missing_epp":[],"timestamp":"2026-06-24T13:30:00",
                         "camera_code":"CAM-01","site_name":"Main-Site","frame_b64":"ZmFrZQ=="}
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        // La validación @NotEmpty corta el flujo antes del controller: el servicio
        // (y por lo tanto la notificación Telegram que orquesta) nunca se invoca.
        verify(service, never()).register(any(), any());

        System.out.println("\n[CP31] Evaluación conforme (missing_epp vacío) — sin incumplimiento:");
        System.out.println("       Respuesta HTTP     = 400 (validación @NotEmpty)");
        System.out.println("       service.register() = nunca invocado");
        System.out.println("[CP31] No se invoca registro ni Telegram => PASA");
    }

    // ── Ingesta activa del CV (POST /api/v1/cv/incidents(/clips)) ────────────

    @Test
    @DisplayName("POST /api/v1/cv/incidents con payload válido retorna 201")
    void registerFromCv_retorna201() {
        when(service.registerFromCv(any(CvIncidentMessage.class), any())).thenReturn(Mono.just(sampleResponse));

        CvIncidentMessage message = new CvIncidentMessage("cv-uuid-1", 3, List.of("helmet", "vest"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", "Main-Site",
                "incidents/2026-08-10/cv-uuid-1/photo.jpg");

        client.post().uri("/api/v1/cv/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<IncidentResponse>>() {});
    }

    @Test
    @DisplayName("POST /api/v1/cv/incidents con worker desconocido retorna 400")
    void registerFromCv_workerDesconocido_retorna400() {
        when(service.registerFromCv(any(CvIncidentMessage.class), any())).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Worker desconocido: 3")));

        CvIncidentMessage message = new CvIncidentMessage("cv-uuid-1", 3, List.of("helmet"),
                LocalDateTime.of(2026, 6, 24, 13, 30), "CAM-01", "Main-Site", "incidents/photo.jpg");

        client.post().uri("/api/v1/cv/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("POST /api/v1/cv/incidents/clips con payload válido retorna 200")
    void registerClip_retorna200() {
        when(service.registerClipReady(any(CvClipReadyMessage.class))).thenReturn(Mono.empty());

        CvClipReadyMessage message = new CvClipReadyMessage("cv-uuid-1",
                "incidents/2026-08-10/cv-uuid-1/clip.mp4", 10.0, 4831201L);

        client.post().uri("/api/v1/cv/incidents/clips")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<Void>>() {});
    }

    @Test
    @DisplayName("POST /api/v1/cv/incidents/clips con incidente desconocido retorna 500 (best-effort)")
    void registerClip_incidenteDesconocido_propagaError() {
        when(service.registerClipReady(any(CvClipReadyMessage.class))).thenReturn(Mono.error(
                new IllegalArgumentException("Incidente desconocido para clip: external_id=cv-uuid-x")));

        CvClipReadyMessage message = new CvClipReadyMessage("cv-uuid-x", "incidents/clip.mp4", 5.0, 100L);

        client.post().uri("/api/v1/cv/incidents/clips")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("GET /api/v1/incidents retorna 200 con lista filtrada")
    void findAll_retorna200() {
        when(service.findByFilter(any(), any(), any(), any())).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/incidents?siteId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<IncidentResponse>>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(100L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/incidents/{id}", 100L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<IncidentResponse>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id} inexistente retorna 404")
    void findById_inexistente_retorna404() {
        when(service.findById(anyLong())).thenReturn(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found")));

        client.get().uri("/api/v1/incidents/{id}", 999L)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/evidence existente retorna 200 con la lista")
    void findEvidence_existente_retorna200() {
        EvidenceResponse evidence = new EvidenceResponse("PHOTO",
                "https://s3.amazonaws.com/bucket/photo.jpg?sig=x", null, null, LocalDateTime.now());
        when(service.findEvidenceByIncidentId(100L)).thenReturn(Flux.just(evidence));

        client.get().uri("/api/v1/incidents/{id}/evidence", 100L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<List<EvidenceResponse>>>() {});
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/evidence de incidente inexistente retorna 404")
    void findEvidence_incidenteInexistente_retorna404() {
        when(service.findEvidenceByIncidentId(999L)).thenReturn(Flux.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found")));

        client.get().uri("/api/v1/incidents/{id}/evidence", 999L)
                .exchange()
                .expectStatus().isNotFound();
    }
}
