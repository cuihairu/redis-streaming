package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GcMetricCollector
 */
class GcMetricCollectorTest {

    @Test
    void testGetMetricType() {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        String type = collector.getMetricType();

        // Then
        assertEquals("gc", type);
    }

    @Test
    void testCollectMetricReturnsMap() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testCollectMetricContainsCount() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("count"));
        assertTrue(metrics.containsKey(MetricKeys.GC_COUNT));
        assertTrue(metrics.get("count") instanceof Long);
    }

    @Test
    void testCollectMetricContainsTime() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("time"));
        assertTrue(metrics.containsKey(MetricKeys.GC_TIME));
        assertTrue(metrics.get("time") instanceof Long);
    }

    @Test
    void testGcCountIsNonNegative() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long count = (Long) metrics.get("count");
        assertTrue(count >= 0);
    }

    @Test
    void testGcTimeIsNonNegative() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long time = (Long) metrics.get("time");
        assertTrue(time >= 0);
    }

    @Test
    void testIsAvailable() {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When & Then
        assertTrue(collector.isAvailable());
    }

    @Test
    void testGetCost() {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When & Then
        assertEquals(CollectionCost.LOW, collector.getCost());
    }

    @Test
    void testMetricKeysConsistency() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - verify both old and new key formats exist and have same values
        assertEquals(metrics.get("count"), metrics.get(MetricKeys.GC_COUNT));
        assertEquals(metrics.get("time"), metrics.get(MetricKeys.GC_TIME));
    }

    @Test
    void testMultipleCollectionsReturnIncreasingOrEqualValues() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        System.gc(); // Suggest garbage collection (may or may not run)
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - count and time should be monotonically increasing or equal
        long count1 = (Long) metrics1.get("count");
        long count2 = (Long) metrics2.get("count");
        assertTrue(count2 >= count1, "GC count should be monotonically increasing");

        long time1 = (Long) metrics1.get("time");
        long time2 = (Long) metrics2.get("time");
        assertTrue(time2 >= time1, "GC time should be monotonically increasing");
    }

    @Test
    void testGcTimeInMilliseconds() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - time should be in milliseconds (reasonable value < 1 hour)
        long time = (Long) metrics.get("time");
        assertTrue(time >= 0);
        // GC time typically shouldn't exceed hours for a healthy application
        assertTrue(time < 3600000, "GC time seems unusually high");
    }

    @Test
    void testGcCountReasonable() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - count should be reasonable (not extremely high for a test)
        long count = (Long) metrics.get("count");
        assertTrue(count >= 0);
        // In tests, GC count shouldn't be in millions
        assertTrue(count < 1000000, "GC count seems unusually high");
    }

    @Test
    void testCollectAfterGcSuggestion() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        System.gc(); // Suggest GC
        Thread.sleep(100); // Give GC time to potentially run
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - values should be valid
        assertNotNull(metrics2);
        assertTrue(metrics2.containsKey("count"));
        assertTrue(metrics2.containsKey("time"));
    }

    @Test
    void testInitialCollection() throws Exception {
        // Given
        GcMetricCollector collector = new GcMetricCollector();

        // When - first collection
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - should return valid metrics even on first collection
        assertNotNull(metrics);
        assertTrue(metrics.containsKey("count"));
        assertTrue(metrics.containsKey("time"));
        assertTrue((Long) metrics.get("count") >= 0);
        assertTrue((Long) metrics.get("time") >= 0);
    }
}
