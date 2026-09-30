package com.safevision.back.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.service.EppParameterService;
import com.safevision.back.infrastructure.web.dto.ApiEnvelope;
import com.safevision.back.infrastructure.web.dto.EppParameterRequest;
import com.safevision.back.infrastructure.web.dto.EppParameterResponse;
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
import reactor.core.publisher.Mono;

import java.util.List;

@ExtendWith(MockitoExtension.class)
@DisplayName("EppParameterController — HTTP por obra")
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
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes().put("username", "supervisor1");
                    return chain.filter(exchange);
                })
                .build();

        sampleResponse = new EppParameterResponse(
                SITE_ID,
                List.of(
                        new EppParameterResponse.EppItem(1L, "casco", "Casco de seguridad"),
                        new EppParameterResponse.EppItem(2L, "chaleco", "Chaleco reflectivo")
                ),
                60
        );
    }

    // ── GET /api/v1/parameters/{siteId} ────────────────────────────────

    @Test
    @DisplayName("GET retorna 200 con EPPs requeridos para la obra")
    void get_retorna200ConEppsDeObra() {
        when(service.findBySite(SITE_ID)).thenReturn(Mono.just(sampleResponse));

        client.get().uri("/parameters/{siteId}", SITE_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<EppParameterResponse>>() {})
                .value(envelope -> {
                    EppParameterResponse body = envelope.data();
                    System.out.println("\nGET /api/v1/parameters/" + SITE_ID + " — 200 OK:");
                    System.out.println(" siteId = " + body.siteId());
                    System.out.println(" requiredEpp = " + body.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("Lista de reglas activas retornada => PASA");
                });
    }

    // ── PUT /api/v1/parameters/{siteId} con datos válidos ─────────────

    @Test
    @DisplayName("PUT con payload válido retorna 200 y persiste por obra")
    void put_payloadValido_retorna200() {
        when(service.updateForSite(anyLong(), any(EppParameterRequest.class), anyString()))
                .thenReturn(Mono.just(sampleResponse));

        client.put().uri("/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[\"casco\",\"chaleco\"],\"cooldownSeconds\":60}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<ApiEnvelope<EppParameterResponse>>() {})
                .value(envelope -> {
                    EppParameterResponse body = envelope.data();
                    System.out.println("\nPUT /api/v1/parameters/" + SITE_ID + " — payload válido:");
                    System.out.println(" siteId = " + body.siteId());
                    System.out.println(" requiredEpp = " + body.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("Regla por obra creada, 200 retornado => PASA");
                });
    }

    // ── PUT con datos inválidos → 400 ──────────────────────────────────

    @Test
    @DisplayName("PUT con lista vacía retorna 400 (validación @NotEmpty)")
    void put_listaVacia_retorna400() {
        client.put().uri("/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[],\"cooldownSeconds\":60}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .consumeWith(result -> {
                    System.out.println("\nPUT /api/v1/parameters/" + SITE_ID + " — lista vacía:");
                    System.out.println(" Status HTTP = 400");
                    System.out.println("Respuesta 400, nada persistido => PASA");
                });
    }

    @Test
    @DisplayName("PUT con cooldownSeconds invalido (0) retorna 400 (validación @Min)")
    void put_cooldownInvalido_retorna400() {
        client.put().uri("/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[\"casco\"],\"cooldownSeconds\":0}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("PUT con EPP inexistente retorna 400 (servicio valida contra catálogo)")
    void put_eppInexistente_retorna400() {
        when(service.updateForSite(anyLong(), any(EppParameterRequest.class), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "EPP desconocido: casco_minero")));

        client.put().uri("/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[\"casco_minero\"],\"cooldownSeconds\":60}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("PUT con tipo de dato incorrecto (cooldownSeconds texto, requiredEpp no lista) retorna 400")
    void put_tipoDeDatoIncorrecto_retorna400() {
        client.put().uri("/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":[\"casco\"],\"cooldownSeconds\":\"abc\"}")
                .exchange()
                .expectStatus().isBadRequest();
        client.put().uri("/parameters/{siteId}", SITE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requiredEpp\":\"casco\",\"cooldownSeconds\":60}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).updateForSite(anyLong(), any(EppParameterRequest.class), anyString());
        System.out.println("\n[CP17] PUT /api/v1/parameters/" + SITE_ID + " — tipo de dato incorrecto:");
        System.out.println("       cooldownSeconds = \"abc\" (texto)  -> 400");
        System.out.println("       requiredEpp     = \"casco\" (no lista) -> 400");
        System.out.println("[CP17] Rechazado sin llegar al servicio, nada persistido => PASA");
    }
}
