package io.github.cuihairu.redis.streaming.metrics.prometheus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PrometheusExporter
 */
class PrometheusExporterTest {

    // ===== Constructor Tests =====

    @Test
    void testConstructorWithEphemeralPort() throws IOException {
        PrometheusExporter exporter = new PrometheusExporter(0);

        try {
            assertTrue(exporter.getPort() > 0);
            assertEquals("http://localhost:" + exporter.getPort() + "/metrics", exporter.getMetricsUrl());
        } finally {
            exporter.close();
        }
    }

    @Test
    void testConstructorThrowsExceptionForInvalidPort() {
        // Port -1 is invalid - HTTPServer throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new PrometheusExporter(-1));
    }

    // ===== getPort Tests =====

    @Test
    void testGetPort() throws IOException {
        PrometheusExporter exporter = new PrometheusExporter(0);

        try {
            assertTrue(exporter.getPort() > 0);
        } finally {
            exporter.close();
        }
    }

    // ===== getMetricsUrl Tests =====

    @Test
    void testGetMetricsUrl() throws IOException {
        PrometheusExporter exporter = new PrometheusExporter(0);

        try {
            String url = exporter.getMetricsUrl();
            assertTrue(url.startsWith("http://localhost:"));
            assertTrue(url.endsWith("/metrics"));
            assertTrue(url.contains(Integer.toString(exporter.getPort())));
        } finally {
            exporter.close();
        }
    }

    // ===== stop Tests =====

    @Test
    void testStopCanBeCalledMultipleTimes() throws IOException {
        PrometheusExporter exporter = new PrometheusExporter(0);

        exporter.stop();
        // Should not throw when called again
        assertDoesNotThrow(() -> exporter.stop());
    }

    // ===== close Tests =====

    @Test
    void testCloseStopsExporter() throws IOException {
        PrometheusExporter exporter = new PrometheusExporter(0);

        assertDoesNotThrow(() -> exporter.close());
        // Closing again should be safe
        assertDoesNotThrow(() -> exporter.close());
    }

    // ===== Integration Tests =====

    @Test
    void testMetricsEndpointIsAccessible() throws IOException, InterruptedException {
        PrometheusExporter exporter = new PrometheusExporter(0);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(exporter.getMetricsUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertNotNull(response.body());

            // Prometheus metrics output should contain some standard content
            String body = response.body();
            // The body may be empty or contain help/type info
        } finally {
            exporter.close();
        }
    }

    @Test
    void testMultipleExportersOnDifferentPorts() throws IOException {
        PrometheusExporter exporter1 = new PrometheusExporter(0);
        PrometheusExporter exporter2 = new PrometheusExporter(0);

        try {
            assertNotEquals(exporter1.getPort(), exporter2.getPort());
            assertNotEquals(exporter1.getMetricsUrl(), exporter2.getMetricsUrl());
        } finally {
            exporter2.close();
            exporter1.close();
        }
    }

    @Test
    void testExporterCanBeRecreatedOnSamePortAfterClose() throws IOException {
        PrometheusExporter exporter1 = new PrometheusExporter(0);
        int port = exporter1.getPort();
        assertTrue(port > 0);
        exporter1.close();

        // Wait a bit for port to be released
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Should be able to create new exporter on same port
        PrometheusExporter exporter2 = new PrometheusExporter(port);
        try {
            assertEquals(port, exporter2.getPort());
        } finally {
            exporter2.close();
        }
    }

    // ===== Edge Cases Tests =====

    @Test
    void testConstructorWithPortZero() {
        // Port 0 typically means "any available port" for HTTPServer
        // In Prometheus HTTPServer, port 0 works - it assigns an available port
        assertDoesNotThrow(() -> {
            PrometheusExporter exporter = new PrometheusExporter(0);
            exporter.close();
        });
    }

    @Test
    void testConstructorWithPort65536() {
        // Port > 65535 is invalid - throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new PrometheusExporter(65536));
    }

    @Test
    void testConstructorWithVeryLargePort() {
        assertThrows(IllegalArgumentException.class, () -> new PrometheusExporter(99999));
    }
}
