package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.safevision.back.application.ports.out.EppParameterRepositoryPort;
import com.safevision.back.application.ports.out.RulesPublisherPort;
import com.safevision.back.application.ports.out.SiteEppConfigVersionRepositoryPort;
import com.safevision.back.application.ports.out.SiteEppRequirementRepositoryPort;
import com.safevision.back.domain.model.EppParameter;
import com.safevision.back.domain.model.SiteEppConfigVersion;
import com.safevision.back.domain.model.SiteEppRequirement;
import com.safevision.back.infrastructure.web.dto.EppParameterRequest;
import com.safevision.back.infrastructure.web.dto.EppParameterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ExtendWith(MockitoExtension.class)
@DisplayName("EppParameterService — por obra")
class EppParameterServiceTest {

    @Mock
    private EppParameterRepositoryPort eppRepo;

    @Mock
    private SiteEppRequirementRepositoryPort siteEppRepo;

    @Mock
    private SiteEppConfigVersionRepositoryPort versionRepo;

    @Mock
    private RulesPublisherPort rulesPublisher;

    private EppParameterService service;

    private static final Long SITE_ID = 1L;

    private final EppParameter casco = epp(1L, "casco", "Casco de seguridad");
    private final EppParameter chaleco = epp(2L, "chaleco", "Chaleco reflectivo");
    private final EppParameter guantes = epp(3L, "guantes", "Guantes de protección");

    @BeforeEach
    void setUp() {
        service = new EppParameterService(eppRepo, siteEppRepo, versionRepo, rulesPublisher);
    }

    private static EppParameter epp(Long id, String code, String name) {
        return new EppParameter(id, code, name, true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    private static SiteEppRequirement req(Long siteId, Long eppId) {
        return new SiteEppRequirement(null, siteId, eppId, LocalDateTime.now(), "system");
    }

    // ── Creación de regla EPP válida para una obra ─────────────────────

    @Test
    @DisplayName("PUT con EPPs válidos persiste la configuración por obra")
    void actualizarEppsPorObra_persiste() {
        EppParameterRequest request = new EppParameterRequest(List.of("casco", "chaleco"));

        when(eppRepo.findByCode("casco")).thenReturn(Mono.just(casco));
        when(eppRepo.findByCode("chaleco")).thenReturn(Mono.just(chaleco));
        when(versionRepo.ensureExists(SITE_ID)).thenReturn(Mono.empty());
        when(versionRepo.findById(SITE_ID)).thenReturn(Mono.just(new SiteEppConfigVersion(SITE_ID, 0L)));
        when(versionRepo.compareAndSwap(SITE_ID, 0L)).thenReturn(Mono.just(1L));
        when(siteEppRepo.deleteAllBySiteId(SITE_ID)).thenReturn(Mono.just(0L));
        when(siteEppRepo.save(any(SiteEppRequirement.class)))
                .thenReturn(Mono.just(req(SITE_ID, 1L)))
                .thenReturn(Mono.just(req(SITE_ID, 2L)));

        StepVerifier.create(service.updateForSite(SITE_ID, request, "supervisor1"))
                .assertNext(response -> {
                    System.out.println("\nRegla EPP por obra actualizada:");
                    System.out.println(" siteId = " + response.siteId());
                    System.out.println(" requiredEpp = " + response.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("Regla válida persistida por obra, 200 => PASA");

                    assertThat(response.siteId()).isEqualTo(SITE_ID);
                    assertThat(response.requiredEpp()).hasSize(2);
                    assertThat(response.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList())
                            .containsExactlyInAnyOrder("casco", "chaleco");
                })
                .verifyComplete();

        verify(siteEppRepo).deleteAllBySiteId(SITE_ID);
        verify(siteEppRepo, times(2)).save(any(SiteEppRequirement.class));
        verify(rulesPublisher).publishRules(eq(SITE_ID), argThat(codes ->
                codes.containsAll(List.of("casco", "chaleco"))));
    }

    // ── Consulta de reglas activas para una obra ────────────────────────

    @Test
    @DisplayName("GET retorna EPPs configurados para la obra")
    void consultarEppsPorObra_retornaLista() {
        when(siteEppRepo.findBySiteId(SITE_ID))
                .thenReturn(Flux.just(req(SITE_ID, 1L), req(SITE_ID, 2L)));
        when(eppRepo.findById(1L)).thenReturn(Mono.just(casco));
        when(eppRepo.findById(2L)).thenReturn(Mono.just(chaleco));

        StepVerifier.create(service.findBySite(SITE_ID))
                .assertNext(response -> {
                    System.out.println("\nReglas EPP activas para obra " + SITE_ID + ":");
                    System.out.println(" requiredEpp = " + response.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("Lista de reglas activas retornada => PASA");

                    assertThat(response.siteId()).isEqualTo(SITE_ID);
                    assertThat(response.requiredEpp()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET con obra sin configuración usa fallback global")
    void sinConfiguracionDeObra_usaFallbackGlobal() {
        when(siteEppRepo.findBySiteId(SITE_ID)).thenReturn(Flux.empty());
        when(eppRepo.findByActiveTrue()).thenReturn(Flux.just(casco, chaleco, guantes));

        StepVerifier.create(service.findBySite(SITE_ID))
                .assertNext(response -> {
                    System.out.println("\nObra sin configuración — fallback global:");
                    System.out.println(" EPPs fallback = " + response.requiredEpp().stream()
                            .map(EppParameterResponse.EppItem::code).toList());
                    System.out.println("Fallback a catálogo global => PASA");

                    assertThat(response.requiredEpp()).hasSize(3);
                })
                .verifyComplete();
    }

    // ── Datos inválidos → 400 ───────────────────────────────────────────

    @Test
    @DisplayName("Lista vacía de EPPs lanza BAD_REQUEST")
    void listaVacia_lanzaBadRequest() {
        EppParameterRequest request = new EppParameterRequest(List.of());

        StepVerifier.create(service.updateForSite(SITE_ID, request, "supervisor1"))
                .expectErrorMatches(ex -> {
                    System.out.println("\nPayload inválido — lista vacía:");
                    System.out.println(" Error: " + ex.getMessage());
                    System.out.println("400 generado, nada persistido => PASA");
                    return ex instanceof ResponseStatusException rse
                           && rse.getStatusCode() == HttpStatus.BAD_REQUEST;
                })
                .verify();

        verify(siteEppRepo, never()).deleteAllBySiteId(anyLong());
        verify(siteEppRepo, never()).save(any());
        verify(rulesPublisher, never()).publishRules(anyLong(), any());
    }

    @Test
    @DisplayName("Código EPP inexistente en catálogo lanza BAD_REQUEST")
    void eppDesconocido_lanzaBadRequest() {
        EppParameterRequest request = new EppParameterRequest(List.of("casco", "casco_minero"));
        when(eppRepo.findByCode("casco")).thenReturn(Mono.just(casco));
        when(eppRepo.findByCode("casco_minero")).thenReturn(Mono.empty());

        StepVerifier.create(service.updateForSite(SITE_ID, request, "supervisor1"))
                .expectErrorMatches(ex ->
                        ex instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                        && rse.getReason().contains("casco_minero"))
                .verify();

        verify(siteEppRepo, never()).deleteAllBySiteId(anyLong());
        verify(rulesPublisher, never()).publishRules(anyLong(), any());
    }

    // ── Edición concurrente sobre la misma obra (optimistic locking) ────

    @Test
    @DisplayName("20 repeticiones de dos PUT reales concurrentes (threads): siempre 1 gana y 1 recibe 409")
    void edicionConcurrente_conflictoDeVersion_20Repeticiones() throws Exception {
        final int REPETICIONES = 20;
        EppParameterRequest req1 = new EppParameterRequest(List.of("casco"));
        EppParameterRequest req2 = new EppParameterRequest(List.of("chaleco"));

        when(eppRepo.findByCode("casco")).thenReturn(Mono.just(casco));
        when(eppRepo.findByCode("chaleco")).thenReturn(Mono.just(chaleco));
        when(versionRepo.ensureExists(SITE_ID)).thenReturn(Mono.empty());
        when(versionRepo.findById(SITE_ID)).thenReturn(Mono.just(new SiteEppConfigVersion(SITE_ID, 0L)));

        // Simula el CAS atómico real (UPDATE ... WHERE version = :expectedVersion en
        // Postgres): con dos threads reales llamando a la vez, solo el primero en
        // ejecutar compareAndSet gana — AtomicBoolean es thread-safe, así que esto
        // resuelve la carrera igual que la fila de la BD lo haría. Se resetea en
        // cada repetición para simular una nueva ronda de concurrencia.
        AtomicBoolean won = new AtomicBoolean(false);
        when(versionRepo.compareAndSwap(eq(SITE_ID), anyLong())).thenAnswer(invocation ->
                won.compareAndSet(false, true) ? Mono.just(1L) : Mono.empty());

        when(siteEppRepo.deleteAllBySiteId(SITE_ID)).thenReturn(Mono.just(1L));
        when(siteEppRepo.save(any(SiteEppRequirement.class))).thenReturn(Mono.just(req(SITE_ID, 1L)));

        System.out.println("\n" + REPETICIONES + " repeticiones de dos PUT reales concurrentes "
                + "(threads) sobre siteId=" + SITE_ID + ":");

        int rondasCorrectas = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int ronda = 1; ronda <= REPETICIONES; ronda++) {
                won.set(false);
                CountDownLatch startLatch = new CountDownLatch(1);
                List<Object> results = Collections.synchronizedList(new ArrayList<>());

                Runnable put1 = () -> {
                    try {
                        startLatch.await();
                        results.add(service.updateForSite(SITE_ID, req1, "user1").block());
                    } catch (Exception e) {
                        results.add(e);
                    }
                };
                Runnable put2 = () -> {
                    try {
                        startLatch.await();
                        results.add(service.updateForSite(SITE_ID, req2, "user2").block());
                    } catch (Exception e) {
                        results.add(e);
                    }
                };

                Future<?> f1 = pool.submit(put1);
                Future<?> f2 = pool.submit(put2);
                startLatch.countDown();
                f1.get(5, TimeUnit.SECONDS);
                f2.get(5, TimeUnit.SECONDS);

                long exitosos = results.stream().filter(r -> r instanceof EppParameterResponse).count();
                long conflictos = results.stream()
                        .filter(r -> r instanceof ResponseStatusException rse
                                && rse.getStatusCode() == HttpStatus.CONFLICT)
                        .count();
                boolean rondaOk = exitosos == 1 && conflictos == 1;
                if (rondaOk) {
                    rondasCorrectas++;
                }
                System.out.println(" Ronda " + ronda + ": exitosos=" + exitosos
                        + " conflictos=" + conflictos + " " + (rondaOk ? "OK" : "FALLA"));
            }
        } finally {
            pool.shutdown();
        }

        System.out.println(rondasCorrectas + "/" + REPETICIONES
                + " rondas con exactamente 1 exitoso y 1 conflicto => "
                + (rondasCorrectas == REPETICIONES ? "PASA" : "FALLA"));

        assertThat(rondasCorrectas).isEqualTo(REPETICIONES);
    }
}
