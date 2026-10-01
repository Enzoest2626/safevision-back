package com.safevision.back.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.application.dto.report.ReportShift;
import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.domain.model.Worker;
import com.safevision.back.domain.model.Zone;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Prueba los agregados de detalle de {@link ReportQueryRepository} contra un
 * Postgres real: EXTRACT(HOUR/ISODOW), heatmap zona×hora, dominantes y salud
 * de notificaciones con filtro de turno. Requiere Docker encendido.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("dev")
class ReportQueryRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void initSchema() throws Exception {
        String schema = Files.readString(Path.of("docs/init-schema.sql"));
        try (Connection conn = jdbc();
                Statement statement = conn.createStatement()) {
            statement.execute(schema);
            statement.execute("INSERT INTO notification_channels (code, name) VALUES ('TELEGRAM', 'Telegram')"
                    + " ON CONFLICT (code) DO NOTHING");
            statement.execute("INSERT INTO notification_statuses (code, name) VALUES ('PENDING', 'Pendiente'),"
                    + " ('SENT', 'Enviada'), ('FAILED', 'Fallida') ON CONFLICT (code) DO NOTHING");
        }
    }

    private static Connection jdbc() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired
    private ReportQueryRepository reportRepository;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private ZoneRepository zoneRepository;
    @Autowired
    private CameraRepository cameraRepository;
    @Autowired
    private WorkerRepository workerRepository;
    @Autowired
    private IncidentRepository incidentRepository;

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 3, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 3, 23, 59, 59);

    private Site siteA;
    private Site siteB;

    @BeforeEach
    void seedFixtures() throws Exception {
        siteA = createSite("Obra A");
        siteB = createSite("Obra B");
        Long workerA = createWorker(siteA.id()).id();
        Long workerB = createWorker(siteB.id()).id();
        Long camA = createCamera(siteA.id(), createZone(siteA.id(), "Zona 1").id()).id();
        Long camB = createCamera(siteB.id(), createZone(siteB.id(), "Zona B").id()).id();

        Long i1 = createIncident(siteA.id(), camA, workerA, LocalDateTime.of(2026, 8, 3, 8, 15),
                new String[] {"helmet", "vest"});
        Long i2 = createIncident(siteA.id(), camA, workerA, LocalDateTime.of(2026, 8, 3, 9, 30),
                new String[] {"helmet"});
        Long i3 = createIncident(siteA.id(), camA, workerA, LocalDateTime.of(2026, 8, 3, 14, 0),
                new String[] {"vest"});
        Long i4 = createIncident(siteB.id(), camB, workerB, LocalDateTime.of(2026, 8, 3, 8, 45),
                new String[] {"gloves"});

        notify(i1, "SENT", LocalDateTime.of(2026, 8, 3, 10, 0));
        notify(i2, "SENT", LocalDateTime.of(2026, 8, 3, 11, 0));
        notify(i3, "FAILED", LocalDateTime.of(2026, 8, 3, 15, 0));
        notify(i4, "FAILED", LocalDateTime.of(2026, 8, 3, 9, 0));
    }

    @Test
    @DisplayName("shift filtra por hora: morning 3, afternoon 1, sin filtro 4")
    void shift_filtraPorHora() {
        assertThat(reportRepository.countIncidents(null, FROM, TO, null).block()).isEqualTo(4L);
        assertThat(reportRepository.countIncidents(null, FROM, TO, ReportShift.MORNING).block()).isEqualTo(3L);
        assertThat(reportRepository.countIncidents(null, FROM, TO, ReportShift.AFTERNOON).block()).isEqualTo(1L);
    }

    @Test
    @DisplayName("countByHour agrupa por hora, weekday por ISO y zoneHour solo trae celdas no-cero")
    void hourlyYZoneHour_agrupanBien() {
        var hours = reportRepository.countByHour(null, FROM, TO, null).collectList().block();
        assertThat(hours).extracting(h -> h.hour() + "=" + h.total())
                .containsExactlyInAnyOrder("8=2", "9=1", "14=1");

        var weekdays = reportRepository.countByWeekday(null, FROM, TO, null).collectList().block();
        assertThat(weekdays).hasSize(1);
        assertThat(weekdays.get(0).total()).isEqualTo(4);
        assertThat(weekdays.get(0).weekday())
                .isEqualTo(java.time.DayOfWeek.from(FROM.toLocalDate()).getValue());

        var cells = reportRepository.countByZoneHour(null, FROM, TO, null).collectList().block();
        assertThat(cells).hasSize(4);
        assertThat(cells).allMatch(c -> c.total() > 0);
        assertThat(cells).extracting(c -> c.zone() + "@" + c.hour())
                .containsExactlyInAnyOrder("Zona 1@8", "Zona 1@9", "Zona 1@14", "Zona B@8");
    }

    @Test
    @DisplayName("dominantes de la obra crítica: EPP helmet y zona Zona 1")
    void dominantes_explicanObraCritica() {
        assertThat(reportRepository.topEppForSite(siteA.name(), FROM, TO, null).block()).isEqualTo("helmet");
        assertThat(reportRepository.topZoneForSite(siteA.name(), FROM, TO, null).block()).isEqualTo("Zona 1");
    }

    @Test
    @DisplayName("notifications: totales, último fallo y obras con fallos (respeta shift)")
    void notifications_conFallas() {
        var health = reportRepository.notificationHealth(null, FROM, TO, null).block();
        assertThat(health.total()).isEqualTo(4);
        assertThat(health.failed()).isEqualTo(2);
        assertThat(health.lastFailedAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 15, 0));
        assertThat(health.failingSites()).containsExactly(siteA.name(), siteB.name());

        var morning = reportRepository.notificationHealth(null, FROM, TO, ReportShift.MORNING).block();
        assertThat(morning.total()).isEqualTo(3);
        assertThat(morning.failed()).isEqualTo(1);
        assertThat(morning.failingSites()).containsExactly(siteB.name());
    }

    private Site createSite(String name) {
        String code = "SITE-" + System.nanoTime();
        return siteRepository.save(new Site(null, code, name + "-" + code, null, null, true, null, null, null, null))
                .block();
    }

    private Zone createZone(Long siteId, String name) {
        String code = "Z-" + System.nanoTime();
        return zoneRepository.save(new Zone(null, siteId, code, name, true, null, null, null, null)).block();
    }

    private Camera createCamera(Long siteId, Long zoneId) {
        String code = "CAM-" + System.nanoTime();
        return cameraRepository.save(new Camera(null, siteId, zoneId, code, "Cam " + code, null, null, true, null,
                null, null, null)).block();
    }

    private Worker createWorker(Long siteId) {
        int code = (int) (System.nanoTime() % 1_000_000);
        return workerRepository.save(new Worker(null, siteId, code, "Nombre", "Test", null, true, null, null, null,
                null)).block();
    }

    private Long createIncident(Long siteId, Long cameraId, Long workerId, LocalDateTime at, String[] epp) {
        return incidentRepository.save(new Incident(null, workerId, cameraId, siteId, "ext-" + System.nanoTime(), epp,
                at, null)).block().id();
    }

    private void notify(Long incidentId, String status, LocalDateTime createdAt) throws Exception {
        try (Connection conn = jdbc();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO notifications"
                        + " (incident_id, channel_id, status_id, sent_at, error_msg, created_at)"
                        + " SELECT ?, c.id, s.id, NULL, NULL, ? FROM notification_channels c,"
                        + " notification_statuses s WHERE c.code = 'TELEGRAM' AND s.code = ?")) {
            ps.setLong(1, incidentId);
            ps.setObject(2, createdAt);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }
}
