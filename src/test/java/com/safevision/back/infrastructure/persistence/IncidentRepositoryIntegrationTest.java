package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Prueba {@code findByFilterPaged}/{@code countByFilter}
 * ({@link com.safevision.back.application.ports.out.IncidentRepositoryPort})
 * contra un Postgres real — el SQL crudo con LIMIT/OFFSET y filtros
 * opcionales (`:siteId IS NULL OR site_id = :siteId`) no lo puede validar un
 * repositorio mockeado; hace falta una base de datos de verdad para confirmar
 * que pagina y cuenta bien.
 */
@SpringBootTest
@Testcontainers
// src/test/resources/application.yml (usado por el resto de los tests, sin
// perfil activo) no trae s3.*/telegram.* — hacen falta para levantar el
// contexto completo. "dev" sí las trae, con sus mismos defaults inofensivos.
@ActiveProfiles("dev")
class IncidentRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                        POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void initSchema() throws Exception {
        // docs/init-schema.sql es la fuente única de verdad del schema (ver
        // CLAUDE.md) — se ejecuta tal cual contra el contenedor, sin copiar
        // una versión paralela a test/resources.
        String schema = Files.readString(Path.of("docs/init-schema.sql"));
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = conn.createStatement()) {
            statement.execute(schema);
        }
    }

    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private CameraRepository cameraRepository;
    @Autowired
    private WorkerRepository workerRepository;

    private Long siteId;
    private Long otherSiteId;

    @BeforeEach
    void seedFixtures() {
        siteId = createSite().id();
        otherSiteId = createSite().id();
        Long cameraId = createCamera(siteId).id();
        Long workerId = createWorker(siteId).id();

        List<LocalDateTime> horas = List.of(
                LocalDateTime.of(2026, 8, 1, 8, 0),
                LocalDateTime.of(2026, 8, 1, 9, 0),
                LocalDateTime.of(2026, 8, 1, 10, 0));
        for (LocalDateTime hora : horas) {
            createIncident(siteId, cameraId, workerId, hora);
        }
        // incidente de otra obra — no debe aparecer al filtrar por siteId ni contarse en su total.
        createIncident(otherSiteId, cameraId, workerId, LocalDateTime.of(2026, 8, 1, 8, 30));
    }

    @Test
    @DisplayName("findByFilterPaged: primera pagina devuelve las mas recientes primero")
    void findByFilterPaged_primeraPagina_ordenaPorFechaDescendente() {
        List<Incident> pagina = incidentRepository
                .findByFilterPaged(siteId, null, dia(0), dia(1), 2, 0)
                .collectList()
                .block();

        assertThat(pagina).hasSize(2);
        assertThat(pagina.get(0).occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
        assertThat(pagina.get(1).occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));
    }

    @Test
    @DisplayName("findByFilterPaged: segunda pagina (offset) devuelve el resto, sin repetir filas")
    void findByFilterPaged_segundaPagina_devuelveElResto() {
        List<Incident> pagina = incidentRepository
                .findByFilterPaged(siteId, null, dia(0), dia(1), 2, 2)
                .collectList()
                .block();

        assertThat(pagina).hasSize(1);
        assertThat(pagina.get(0).occurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 8, 0));
    }

    @Test
    @DisplayName("countByFilter: cuenta el total real de la obra, no la otra, independiente del tamaño de pagina")
    void countByFilter_cuentaPorObraSinMezclarOtrasObras() {
        Long totalSiteA = incidentRepository.countByFilter(siteId, null, dia(0), dia(1)).block();
        Long totalSinFiltroDeObra = incidentRepository.countByFilter(null, null, dia(0), dia(1)).block();

        assertThat(totalSiteA).isEqualTo(3L);
        assertThat(totalSinFiltroDeObra).isEqualTo(4L);
    }

    private static LocalDateTime dia(int offsetDias) {
        return LocalDateTime.of(2026, 8, 1 + offsetDias, 0, 0);
    }

    private Site createSite() {
        String code = "SITE-" + System.nanoTime();
        return siteRepository.save(new Site(null, code, "Obra " + code, null, null, true, null, null, null, null)).block();
    }

    private Camera createCamera(Long siteId) {
        String code = "CAM-" + System.nanoTime();
        return cameraRepository
                .save(new Camera(null, siteId, null, code, "Camara " + code, null, null, true, null, null, null, null))
                .block();
    }

    private Worker createWorker(Long siteId) {
        int code = (int) (System.nanoTime() % 1_000_000);
        return workerRepository
                .save(new Worker(null, siteId, code, "Trabajador", "Test", null, true, null, null, null, null))
                .block();
    }

    private void createIncident(Long siteId, Long cameraId, Long workerId, LocalDateTime occurredAt) {
        incidentRepository
                .save(new Incident(null, workerId, cameraId, siteId, "ext-" + System.nanoTime(),
                        new String[] {"casco"}, occurredAt, null))
                .block();
    }
}
