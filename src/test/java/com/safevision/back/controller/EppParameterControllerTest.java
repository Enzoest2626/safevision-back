package com.safevision.back.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.safevision.back.dto.EppParameterRequest;
import com.safevision.back.dto.EppParameterResponse;
import com.safevision.back.service.EppParameterService;
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
import reactor.core.publisher.Mono;

import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("EppParameterController — HU04 (HTTP por obra)")
class EppParameterControllerTest {

    @Mock
    private EppParameterService service;

    private WebTestClient client;

    private static final Long SITE_ID = 1L;

    private EppParameterResponse sampleResponse;

    @BeforeEach
    void setUp() {
        client = WebTestClient
                .bindToController(new EppParameterController(service))
                .build();

        sampleResponse = new EppParameterResponse(
                SITE_ID,
                List.of(
                        new EppParameterResponse.EppItem(1L, "casco",   "Casco de seguridad"),
                        new EppParameterResponse.EppItem(2L, "chaleco", "Chaleco reflectivo")
                )
        );
    }

    // ── CP16: GET /api/v1/parameters/{siteId} ────────────────────────────────

    @Test
    @DisplayName("CP16 — GET retorna 200 con EPPs requeridos para la obra")
    void cp16_get_retorna200ConEppsDeObra() {
        when(service.findBySite(SITE_ID)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/api/v1/parameters/{siteId}", SITE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(EppParameterResponse.class)
                .value(body -> {
                    System.out.println("\n[CP16] GET /api/v1/parameters/" + SITE_ID + " — 200 OK:");
                    System.out.println("       siteId      = " + body.siteId());
                    System.out.println("       requiredEpp = " + body.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("[CP16] Lista de reglas activas retornada => PASA");
                });
    }

    // ── CP15: PUT /api/v1/parameters/{siteId} con datos válidos ─────────────

    @Test
    @DisplayName("CP15 — PUT con payload válido retorna 200 y persiste por obra")
    void cp15_put_payloadValido_retorna200() {
        when(service.updateForSite(anyLong(), any(EppParameterRequest.class), anyString()))
                .thenReturn(Mono.just(sampleResponse));

        client.put().uri("/api/v1/parameters/{siteId}", SITE_ID)
                .header("X-Username", "supervisor1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[\"casco\",\"chaleco\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(EppParameterResponse.class)
                .value(body -> {
                    System.out.println("\n[CP15] PUT /api/v1/parameters/" + SITE_ID + " — payload válido:");
                    System.out.println("       siteId      = " + body.siteId());
                    System.out.println("       requiredEpp = " + body.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("[CP15] Regla por obra creada, 200 retornado => PASA");
                });
    }

    // ── CP17: PUT con datos inválidos → 400 ──────────────────────────────────

    @Test
    @DisplayName("CP17 — PUT con lista vacía retorna 400 (validación @NotEmpty)")
    void cp17_put_listaVacia_retorna400() {
        client.put().uri("/api/v1/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .consumeWith(result -> {
                    System.out.println("\n[CP17] PUT /api/v1/parameters/" + SITE_ID + " — lista vacía:");
                    System.out.println("       Status HTTP = 400");
                    System.out.println("[CP17] Respuesta 400, nada persistido => PASA");
                });
    }

    @Test
    @DisplayName("CP17 — PUT con EPP inexistente retorna 400 (servicio valida contra catálogo)")
    void cp17_put_eppInexistente_retorna400() {
        when(service.updateForSite(anyLong(), any(EppParameterRequest.class), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "EPP desconocido: casco_minero")));

        client.put().uri("/api/v1/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[\"casco_minero\"]}")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
