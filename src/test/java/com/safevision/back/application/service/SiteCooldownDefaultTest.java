package com.safevision.back.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.application.ports.out.SiteRepositoryPort;
import com.safevision.back.domain.model.Site;
import com.safevision.back.infrastructure.web.dto.SiteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * CP36 — el cooldown por defecto (HU07): toda obra creada debe guardar
 * {@code cooldownSeconds = 30} desde la capa de aplicación, no solo gracias
 * al {@code DEFAULT 30} de la columna (el backend envía el valor explícito
 * y así el CV recibe siempre un cooldown conocido).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteCooldownDefaultTest — cooldown por defecto al crear una obra")
class SiteCooldownDefaultTest {

    @Mock
    private SiteRepositoryPort siteRepository;

    private SiteService service;

    @BeforeEach
    void setUp() {
        service = new SiteService(siteRepository);
    }

    @Test
    @Tag("CP36")
    @DisplayName("CP36 — create persiste cooldownSeconds = 30 (Site.DEFAULT_COOLDOWN_SECONDS)")
    void cp36_create_persisteCooldownPorDefecto() {
        SiteRequest request = new SiteRequest("OBRA-CP36", "Obra CP36", "Lima");
        when(siteRepository.existsByCode("OBRA-CP36")).thenReturn(Mono.just(false));
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        System.out.println("\n[CP36] SiteService.create — obra nueva, sin cooldownSeconds en el request:");

        StepVerifier.create(service.create(request, "supervisor1"))
                .assertNext(response -> assertThat(response.name()).isEqualTo("Obra CP36"))
                .verifyComplete();

        ArgumentCaptor<Site> captor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).save(captor.capture());
        Site guardado = captor.getValue();

        System.out.println("       Site guardado (code)        = " + guardado.code());
        System.out.println("       cooldownSeconds guardado     = " + guardado.cooldownSeconds());
        System.out.println("       Site.DEFAULT_COOLDOWN_SECONDS = " + Site.DEFAULT_COOLDOWN_SECONDS);

        assertThat(guardado.cooldownSeconds()).isEqualTo(30);
        assertThat(Site.DEFAULT_COOLDOWN_SECONDS).isEqualTo(30);

        System.out.println("[CP36] Obra persistida con cooldown de 30 s por defecto => PASA");
    }
}
