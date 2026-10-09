package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * CP46/CP47 — columna {@code sites.cooldown_seconds} (HU07) contra un
 * Postgres real.
 *
 * <p>Sin levantar Spring: solo JDBC puro y {@code docs/init-schema.sql}
 * tal cual. Lo que importa acá es que el {@code DEFAULT 30} y el
 * {@code CHECK (cooldown_seconds > 0)} existan de verdad en la columna —
 * ni un repositorio mockeado ni una aplicación con otra definición de la
 * tabla pueden probarlo.
 */
@Testcontainers
class SiteCooldownSchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void initSchema() throws Exception {
        // docs/init-schema.sql es la fuente única de verdad del schema — se
        // ejecuta tal cual contra el contenedor, sin copia paralela en
        // test/resources (mismo criterio que IncidentRepositoryIntegrationTest).
        String schema = Files.readString(Path.of("docs/init-schema.sql"));
        try (Connection conn = open(); Statement statement = conn.createStatement()) {
            statement.execute(schema);
        }
    }

    @Test
    @Tag("CP46")
    @DisplayName("CP46 — INSERT sin cooldown_seconds aplica el DEFAULT 30")
    void cp46_insertSinCooldown_aplicaDefault30() throws SQLException {
        String code = "CP46-" + System.nanoTime();
        System.out.println("\n[CP46] INSERT INTO sites (code, name) sin cooldown_seconds — Postgres real:");

        int cooldown;
        try (Connection conn = open(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("INSERT INTO sites (code, name) VALUES ('" + code + "', 'Obra CP46')");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT cooldown_seconds FROM sites WHERE code = '" + code + "'")) {
                assertThat(rs.next()).isTrue();
                cooldown = rs.getInt("cooldown_seconds");
            }
        }

        System.out.println("       code             = " + code);
        System.out.println("       cooldown_seconds = " + cooldown);

        assertThat(cooldown).isEqualTo(30);

        System.out.println("[CP46] DEFAULT 30 aplicado en el INSERT => PASA");
    }

    @Test
    @Tag("CP47")
    @DisplayName("CP47 — INSERT con cooldown_seconds = 0 o -5 viola el CHECK y lanza SQLException")
    void cp47_insertCooldownNoPositivo_lanzaSqlException() {
        System.out.println("\n[CP47] INSERT con cooldown_seconds = 0 y -5 — Postgres real:");

        Throwable errorCero = catchThrowable(() -> insertConCooldown(0));
        Throwable errorNegativo = catchThrowable(() -> insertConCooldown(-5));

        assertThat(errorCero).isInstanceOf(SQLException.class);
        assertThat(errorNegativo).isInstanceOf(SQLException.class);

        String mensajeCero = errorCero.getMessage();
        String mensajeNegativo = errorNegativo.getMessage();

        System.out.println("       cooldown_seconds = 0  -> SQLException: " + mensajeCero);
        System.out.println("       cooldown_seconds = -5 -> SQLException: " + mensajeNegativo);

        assertThat(mensajeCero).contains("cooldown_seconds");
        assertThat(mensajeNegativo).contains("cooldown_seconds");

        System.out.println("[CP47] CHECK (cooldown_seconds > 0) rechaza 0 y -5 => PASA");
    }

    /** Intenta insertar una obra con el cooldown dado; el CHECK del schema debe rechazarlo. */
    private static void insertConCooldown(int cooldown) throws SQLException {
        String code = "CP47-" + System.nanoTime();
        try (Connection conn = open(); Statement statement = conn.createStatement()) {
            statement.executeUpdate("INSERT INTO sites (code, name, cooldown_seconds) VALUES ('"
                    + code + "', 'Obra CP47', " + cooldown + ")");
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
