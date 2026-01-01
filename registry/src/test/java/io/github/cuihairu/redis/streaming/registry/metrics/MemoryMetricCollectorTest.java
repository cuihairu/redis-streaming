package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MemoryMetricCollector
 */
class MemoryMetricCollectorTest {

    @Test
    void testGetMetricType() {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        String type = collector.getMetricType();

        // Then
        assertEquals("memory", type);
    }

    @Test
    void testCollectMetricReturnsMap() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testCollectMetricContainsHeapMetrics() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("heap_used"));
        assertTrue(metrics.containsKey(MetricKeys.MEMORY_HEAP_USED));
        assertTrue(metrics.containsKey("heap_max"));
        assertTrue(metrics.containsKey(MetricKeys.MEMORY_HEAP_MAX));
        assertTrue(metrics.containsKey("heap_committed"));
        assertTrue(metrics.containsKey("heap_usagePercent"));
        assertTrue(metrics.containsKey(MetricKeys.MEMORY_HEAP_USAGE_PERCENT));
    }

    @Test
    void testCollectMetricContainsNonHeapMetrics() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("nonHeap_used"));
        assertTrue(metrics.containsKey(MetricKeys.MEMORY_NON_HEAP_USED));
        assertTrue(metrics.containsKey("nonHeap_max"));
        assertTrue(metrics.containsKey("nonHeap_committed"));
        assertTrue(metrics.containsKey("nonHeap_usagePercent"));
        assertTrue(metrics.containsKey(MetricKeys.MEMORY_NON_HEAP_USAGE_PERCENT));
    }

    @Test
    void testHeapUsedIsNonNegative() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long heapUsed = (Long) metrics.get("heap_used");
        assertTrue(heapUsed >= 0);
    }

    @Test
    void testHeapMaxIsPositive() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long heapMax = (Long) metrics.get("heap_max");
        assertTrue(heapMax > 0);
    }

    @Test
    void testHeapCommittedIsPositive() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long heapCommitted = (Long) metrics.get("heap_committed");
        assertTrue(heapCommitted > 0);
    }

    @Test
    void testHeapUsagePercentIsBetween0And100() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        double heapUsagePercent = (Double) metrics.get("heap_usagePercent");
        assertTrue(heapUsagePercent >= 0.0);
        assertTrue(heapUsagePercent <= 100.0);
    }

    @Test
    void testNonHeapUsedIsNonNegative() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long nonHeapUsed = (Long) metrics.get("nonHeap_used");
        assertTrue(nonHeapUsed >= 0);
    }

    @Test
    void testNonHeapUsagePercentIsNonNegative() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        double nonHeapUsagePercent = (Double) metrics.get("nonHeap_usagePercent");
        assertTrue(nonHeapUsagePercent >= 0.0);
    }

    @Test
    void testIsAvailable() {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When & Then
        assertTrue(collector.isAvailable());
    }

    @Test
    void testGetCost() {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When & Then
        assertEquals(CollectionCost.LOW, collector.getCost());
    }

    @Test
    void testHeapUsedLessOrEqualToMax() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long heapUsed = (Long) metrics.get("heap_used");
        long heapMax = (Long) metrics.get("heap_max");
        assertTrue(heapUsed <= heapMax);
    }

    @Test
    void testHeapCommittedLessOrEqualToMax() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long heapCommitted = (Long) metrics.get("heap_committed");
        long heapMax = (Long) metrics.get("heap_max");
        assertTrue(heapCommitted <= heapMax);
    }

    @Test
    void testMetricKeysConsistency() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - verify both old and new key formats exist and have same values
        assertEquals(metrics.get("heap_used"), metrics.get(MetricKeys.MEMORY_HEAP_USED));
        assertEquals(metrics.get("heap_max"), metrics.get(MetricKeys.MEMORY_HEAP_MAX));
        assertEquals(metrics.get("heap_usagePercent"), metrics.get(MetricKeys.MEMORY_HEAP_USAGE_PERCENT));
        assertEquals(metrics.get("nonHeap_used"), metrics.get(MetricKeys.MEMORY_NON_HEAP_USED));
        assertEquals(metrics.get("nonHeap_usagePercent"), metrics.get(MetricKeys.MEMORY_NON_HEAP_USAGE_PERCENT));
    }

    @Test
    void testMultipleCollectionsReturnDifferentValues() throws Exception {
        // Given
        MemoryMetricCollector collector = new MemoryMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        // Allocate some memory
        byte[] memory = new byte[1024 * 1024]; // 1 MB
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - heap usage should have increased or stayed same
        long heapUsed1 = (Long) metrics1.get("heap_used");
        long heapUsed2 = (Long) metrics2.get("heap_used");
        assertTrue(heapUsed2 >= heapUsed1);
    }
}
