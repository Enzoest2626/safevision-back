package com.safevision.back.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.safevision.back.domain.model.Camera;
import com.safevision.back.domain.model.Incident;
import com.safevision.back.domain.model.Site;
import com.safevision.back.infrastructure.config.TelegramProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@DisplayName("TelegramNotificationService — benchmark reproducible")
class TelegramNotificationPerformanceTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASURED_ITERATIONS = 1000;
    private static final int SEQUENTIAL_ITERATIONS = 500;
    private static final int CONCURRENT_ITERATIONS = MEASURED_ITERATIONS - SEQUENTIAL_ITERATIONS;
    private static final int CONCURRENCY = 32;
    private static final long THRESHOLD_MS = 5000L;
    private static final String FRAME_B64 = "ZmFrZS1qcGVnLWJ5dGVz";

    private HttpServer server;
    private AtomicInteger receivedRequests;
    private AtomicBoolean invalidRequest;
    private TelegramNotificationService service;
    private Incident incident;
    private Camera camera;
    private Site site;

    @BeforeEach
    void setUp() throws IOException {
        receivedRequests = new AtomicInteger();
        invalidRequest = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handleTelegramRequest);
        server.setExecutor(Executors.newFixedThreadPool(CONCURRENCY));
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        service = new TelegramNotificationService(
                WebClient.builder(), new TelegramProperties("bot-token-123", "chat-1"), baseUrl);
        incident = new Incident(100L, 10L, 20L, 1L, "ext-100", new String[]{"helmet", "vest"},
                LocalDateTime.of(2026, 6, 24, 13, 30), LocalDateTime.now());
        camera = new Camera(20L, 1L, null, "CAM-01", "Entrada", "10.0.0.5", null,
                true, LocalDateTime.now(), "system", LocalDateTime.now(), "system");
        site = new Site(1L, "OBRA-1", "Main-Site", "Lima", 60, true,
                LocalDateTime.now(), "system", LocalDateTime.now(), "system");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("100 warmups + 1000 samples: reports P95 and enforces the 5 second SLA")
    void benchmark_reproducible_calculaPercentilesYValidaSla() throws Exception {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sendOne(i).block();
        }

        List<Double> timesMs = new ArrayList<>(MEASURED_ITERATIONS);
        for (int i = 0; i < SEQUENTIAL_ITERATIONS; i++) {
            timesMs.add(measureOne(i));
        }

        List<Double> concurrentTimes = Flux.range(0, CONCURRENT_ITERATIONS)
                .flatMap(i -> reactor.core.publisher.Mono.fromCallable(() -> measureOne(1000 + i)),
                        CONCURRENCY)
                .collectList()
                .block();
        assertThat(concurrentTimes).isNotNull().hasSize(CONCURRENT_ITERATIONS);
        timesMs.addAll(concurrentTimes);

        Statistics statistics = Statistics.from(timesMs);
        writeReports(statistics);

        assertThat(receivedRequests.get()).isEqualTo(WARMUP_ITERATIONS + MEASURED_ITERATIONS);
        assertThat(invalidRequest.get()).isFalse();
        assertThat(statistics.p95Ms()).isLessThanOrEqualTo(THRESHOLD_MS);
    }

    private double measureOne(int sequence) {
        long start = System.nanoTime();
        sendOne(sequence).block();
        return nanosToMs(System.nanoTime() - start);
    }

    private reactor.core.publisher.Mono<Void> sendOne(int sequence) {
        return service.sendIncidentAlert("chat-1", incident, camera, site, "", FRAME_B64);
    }

    private void handleTelegramRequest(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        boolean valid = "POST".equals(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().equals("/botbot-token-123/sendPhoto")
                && contentType != null && contentType.startsWith("multipart/form-data;")
                && new String(body, StandardCharsets.UTF_8).contains("chat_id");
        if (!valid) {
            invalidRequest.set(true);
        }
        receivedRequests.incrementAndGet();
        byte[] response = "{\"ok\":true,\"result\":{\"message_id\":1}}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(valid ? 200 : 400, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private void writeReports(Statistics statistics) throws IOException {
        Path outputDir = Path.of("target", "performance");
        Files.createDirectories(outputDir);
        String json = String.format(Locale.ROOT,
                "{\n"
                        + " \"flow\": \"Backend -> Telegram API\",\n"
                        + " \"environment\": \"JDK %s, local JDK HttpServer\",\n"
                        + " \"warmupIterations\": %d,\n"
                        + " \"measuredIterations\": %d,\n"
                        + " \"concurrency\": %d,\n"
                        + " \"minimumMs\": %.3f,\n"
                        + " \"averageMs\": %.3f,\n"
                        + " \"medianMs\": %.3f,\n"
                        + " \"p95Ms\": %.3f,\n"
                        + " \"p99Ms\": %.3f,\n"
                        + " \"maximumMs\": %.3f,\n"
                        + " \"standardDeviationMs\": %.3f,\n"
                        + " \"thresholdMs\": %d,\n"
                        + " \"status\": \"%s\"\n"
                        + "}\n",
                System.getProperty("java.version"), WARMUP_ITERATIONS, MEASURED_ITERATIONS,
                CONCURRENCY, statistics.minimumMs(), statistics.averageMs(), statistics.medianMs(),
                statistics.p95Ms(), statistics.p99Ms(), statistics.maximumMs(),
                statistics.standardDeviationMs(), THRESHOLD_MS,
                statistics.p95Ms() <= THRESHOLD_MS ? "PASS" : "FAIL");
        Files.writeString(outputDir.resolve("sprint2-telegram-performance.json"), json);

        String txt = "SafeVision Sprint 2 — Telegram performance benchmark\n"
                + "Flow: Backend -> Telegram API\n"
                + "Warmup: " + WARMUP_ITERATIONS + " | measured: " + MEASURED_ITERATIONS
                + " | concurrency: " + CONCURRENCY + "\n"
                + String.format(Locale.ROOT,
                "Minimum: %.3f ms\nAverage: %.3f ms\nMedian: %.3f ms\n"
                        + "P95: %.3f ms\nP99: %.3f ms\nMaximum: %.3f ms\n"
                        + "Std. deviation: %.3f ms\nThreshold: %d ms\nStatus: %s\n",
                statistics.minimumMs(), statistics.averageMs(), statistics.medianMs(),
                statistics.p95Ms(), statistics.p99Ms(), statistics.maximumMs(),
                statistics.standardDeviationMs(), THRESHOLD_MS,
                statistics.p95Ms() <= THRESHOLD_MS ? "PASS" : "FAIL");
        Files.writeString(outputDir.resolve("sprint2-telegram-performance.txt"), txt);
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Statistics(double minimumMs, double averageMs, double medianMs, double p95Ms,
                              double p99Ms, double maximumMs, double standardDeviationMs) {

        private static Statistics from(List<Double> values) {
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            double average = sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double variance = sorted.stream().mapToDouble(value -> Math.pow(value - average, 2))
                    .average().orElseThrow();
            return new Statistics(sorted.getFirst(), average, percentile(sorted, 0.50),
                    percentile(sorted, 0.95), percentile(sorted, 0.99), sorted.getLast(),
                    Math.sqrt(variance));
        }

        private static double percentile(List<Double> sorted, double percentile) {
            double position = percentile * (sorted.size() - 1);
            int lower = (int) Math.floor(position);
            int upper = (int) Math.ceil(position);
            if (lower == upper) {
                return sorted.get(lower);
            }
            double fraction = position - lower;
            return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
        }
    }
}
