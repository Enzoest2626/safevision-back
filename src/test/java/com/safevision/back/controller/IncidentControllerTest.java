package com.safevision.back.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.dto.IncidentRequest;
import com.safevision.back.dto.IncidentResponse;
import com.safevision.back.service.IncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
                .expectBody(IncidentResponse.class);
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

    @Test
    @DisplayName("GET /api/v1/incidents retorna 200 con lista filtrada")
    void findAll_retorna200() {
        when(service.findByFilter(any(), any(), any(), any())).thenReturn(Flux.just(sampleResponse));

        client.get().uri("/api/v1/incidents?siteId=1")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(IncidentResponse.class).hasSize(1);
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id} existente retorna 200")
    void findById_existente_retorna200() {
        when(service.findById(100L)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/incidents/{id}", 100L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IncidentResponse.class);
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
}
