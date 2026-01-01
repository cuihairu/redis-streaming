package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NetworkMetricCollector
 */
class NetworkMetricCollectorTest {

    @Test
    void testGetMetricType() {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        String type = collector.getMetricType();

        // Then
        assertEquals("network", type);
    }

    @Test
    void testCollectMetricReturnsMap() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testCollectMetricContainsRxBytes() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - rxBytes may be present on Linux systems
        if (metrics.containsKey("rxBytes")) {
            assertTrue(metrics.get("rxBytes") instanceof Long);
            assertTrue((Long) metrics.get("rxBytes") >= 0);
        }
        // On non-Linux systems, /proc/net/dev may not exist
    }

    @Test
    void testCollectMetricContainsTxBytes() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - txBytes may be present on Linux systems
        if (metrics.containsKey("txBytes")) {
            assertTrue(metrics.get("txBytes") instanceof Long);
            assertTrue((Long) metrics.get("txBytes") >= 0);
        }
        // On non-Linux systems, /proc/net/dev may not exist
    }

    @Test
    void testRxBytesIsNonNegative() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - only check if present (Linux systems)
        if (metrics.containsKey("rxBytes")) {
            long rxBytes = (Long) metrics.get("rxBytes");
            assertTrue(rxBytes >= 0, "RX bytes should be non-negative");
        }
    }

    @Test
    void testTxBytesIsNonNegative() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - only check if present (Linux systems)
        if (metrics.containsKey("txBytes")) {
            long txBytes = (Long) metrics.get("txBytes");
            assertTrue(txBytes >= 0, "TX bytes should be non-negative");
        }
    }

    @Test
    void testIsAvailable() {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When & Then
        assertTrue(collector.isAvailable());
    }

    @Test
    void testGetCost() {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When & Then
        assertEquals(CollectionCost.LOW, collector.getCost());
    }

    @Test
    void testMayContainTomcatRequests() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - requests may be present if Tomcat is running
        if (metrics.containsKey("requests")) {
            assertTrue(metrics.containsKey(MetricKeys.NETWORK_REQUESTS));
            assertTrue(metrics.get("requests") instanceof Long);
            assertTrue((Long) metrics.get("requests") >= 0);
        }
    }

    @Test
    void testMayContainTomcatErrors() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - errors may be present if Tomcat is running
        if (metrics.containsKey("errors")) {
            assertTrue(metrics.containsKey(MetricKeys.NETWORK_ERRORS));
            assertTrue(metrics.get("errors") instanceof Long);
            assertTrue((Long) metrics.get("errors") >= 0);
        }
    }

    @Test
    void testMayContainTomcatConnections() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - connections may be present if Tomcat is running
        if (metrics.containsKey("connections")) {
            assertTrue(metrics.containsKey(MetricKeys.NETWORK_CONNECTIONS));
            assertTrue(metrics.get("connections") instanceof Long);
            assertTrue((Long) metrics.get("connections") >= 0);
        }
    }

    @Test
    void testMetricKeysConsistency() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - verify both old and new key formats exist and have same values when present
        if (metrics.containsKey("requests")) {
            assertEquals(metrics.get("requests"), metrics.get(MetricKeys.NETWORK_REQUESTS));
        }
        if (metrics.containsKey("errors")) {
            assertEquals(metrics.get("errors"), metrics.get(MetricKeys.NETWORK_ERRORS));
        }
        if (metrics.containsKey("connections")) {
            assertEquals(metrics.get("connections"), metrics.get(MetricKeys.NETWORK_CONNECTIONS));
        }
    }

    @Test
    void testMultipleCollectionsReturnIncreasingOrEqualValues() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - rx/tx bytes should be monotonically increasing or equal (if present)
        if (metrics1.containsKey("rxBytes") && metrics2.containsKey("rxBytes")) {
            long rxBytes1 = (Long) metrics1.get("rxBytes");
            long rxBytes2 = (Long) metrics2.get("rxBytes");
            assertTrue(rxBytes2 >= rxBytes1, "RX bytes should be monotonically increasing");
        }
        if (metrics1.containsKey("txBytes") && metrics2.containsKey("txBytes")) {
            long txBytes1 = (Long) metrics1.get("txBytes");
            long txBytes2 = (Long) metrics2.get("txBytes");
            assertTrue(txBytes2 >= txBytes1, "TX bytes should be monotonically increasing");
        }
    }

    @Test
    void testCollectMetricDoesNotThrow() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When & Then - should not throw exception even without Tomcat
        assertDoesNotThrow(() -> collector.collectMetric());
    }

    @Test
    void testRxBytesAndTxBytesAreIndependent() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - both should be present independently on Linux systems
        // On non-Linux, /proc/net/dev may not exist
        boolean hasRx = metrics.containsKey("rxBytes");
        boolean hasTx = metrics.containsKey("txBytes");
        // Either both present (Linux) or both absent (non-Linux)
        if (hasRx || hasTx) {
            assertTrue(hasRx && hasTx, "If one network byte metric is present, both should be");
        }
    }

    @Test
    void testTomcatMetricsConsistency() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - if errors exist, requests should also exist
        if (metrics.containsKey("errors")) {
            assertTrue(metrics.containsKey("requests") || metrics.containsKey("connections"),
                    "If errors are present, requests or connections should also be present");
        }
    }

    @Test
    void testNetworkBytesAreLargeValues() throws Exception {
        // Given
        NetworkMetricCollector collector = new NetworkMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - values can be large (network bytes accumulate over time) if present
        if (metrics.containsKey("rxBytes")) {
            long rxBytes = (Long) metrics.get("rxBytes");
            assertTrue(rxBytes >= 0);
        }
        if (metrics.containsKey("txBytes")) {
            long txBytes = (Long) metrics.get("txBytes");
            assertTrue(txBytes >= 0);
        }
        // No upper bound check as network bytes can accumulate
    }
}
