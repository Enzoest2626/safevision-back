package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.Site;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * CP18 contra un Postgres real: {@code compareAndSwap} de
 * {@link com.safevision.back.application.ports.out.SiteEppConfigVersionRepositoryPort}.
 *
 * <p>{@code EppParameterServiceTest} ya prueba que el servicio traduce un CAS
 * perdido en 409 CONFLICT, pero ahí el CAS está simulado con un
 * {@code AtomicBoolean}. Lo que solo una base de datos de verdad puede probar es
 * que el {@code UPDATE ... WHERE version = :expectedVersion} sea atómico: dos
 * PUT concurrentes que leyeron la misma versión nunca pueden ganar los dos.
 */
@SpringBootTest
@Testcontainers
// Mismo motivo que IncidentRepositoryIntegrationTest: "dev" trae s3.*/telegram.*.
@ActiveProfiles("dev")
class SiteEppConfigVersionIntegrationTest {

    private static final int ROUNDS = 20;

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
        String schema = Files.readString(Path.of("docs/init-schema.sql"));
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = conn.createStatement()) {
            statement.execute(schema);
        }
    }

    @Autowired
    private SiteEppConfigVersionRepository versionRepository;
    @Autowired
    private SiteRepository siteRepository;

    @Test
    @DisplayName("CP18 — 20 rondas de dos CAS concurrentes con la misma versión: siempre gana exactamente uno")
    void compareAndSwap_concurrenteMismaVersion_soloUnoGana() throws Exception {
        String code = "SITE-" + System.nanoTime();
        Long siteId = siteRepository
                .save(new Site(null, code, "Obra " + code, null, null, true, null, null, null, null))
                .block()
                .id();
        versionRepository.ensureExists(siteId).block();

        System.out.println("\n[CP18] Edición concurrente sobre la misma obra (siteId=" + siteId
                + ") — Postgres real, " + ROUNDS + " rondas:");
        int correctRounds = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                long expected = versionRepository.findById(siteId).block().version();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Optional<Long>>> puts = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    puts.add(pool.submit(() -> {
                        start.await();
                        return versionRepository.compareAndSwap(siteId, expected).blockOptional();
                    }));
                }
                start.countDown();

                long winners = 0;
                for (Future<Optional<Long>> put : puts) {
                    winners += put.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0;
                }
                long finalVersion = versionRepository.findById(siteId).block().version();
                boolean ok = winners == 1 && finalVersion == expected + 1;
                correctRounds += ok ? 1 : 0;
                System.out.println("       Ronda " + round + ": versión " + expected + " -> " + finalVersion
                        + " | ganadores=" + winners + " conflictos=" + (2 - winners) + (ok ? " OK" : " FALLA"));
            }
        } finally {
            pool.shutdown();
        }

        System.out.println("[CP18] " + correctRounds + "/" + ROUNDS
                + " rondas con exactamente 1 ganador y 1 conflicto (409) => "
                + (correctRounds == ROUNDS ? "PASA" : "FALLA"));
        assertThat(correctRounds).isEqualTo(ROUNDS);
    }
}
