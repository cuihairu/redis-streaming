package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiskMetricCollector
 */
class DiskMetricCollectorTest {

    @Test
    void testGetMetricType() {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        String type = collector.getMetricType();

        // Then
        assertEquals("disk", type);
    }

    @Test
    void testCollectMetricReturnsMap() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testCollectMetricContainsTotalSpace() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("totalSpace"));
        assertTrue(metrics.containsKey(MetricKeys.DISK_TOTAL_SPACE));
        assertTrue(metrics.get("totalSpace") instanceof Long);
    }

    @Test
    void testCollectMetricContainsFreeSpace() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("freeSpace"));
        assertTrue(metrics.containsKey(MetricKeys.DISK_FREE_SPACE));
        assertTrue(metrics.get("freeSpace") instanceof Long);
    }

    @Test
    void testCollectMetricContainsUsedSpace() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("usedSpace"));
        assertTrue(metrics.containsKey(MetricKeys.DISK_USED_SPACE));
        assertTrue(metrics.get("usedSpace") instanceof Long);
    }

    @Test
    void testCollectMetricContainsUsagePercent() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("usagePercent"));
        assertTrue(metrics.containsKey(MetricKeys.DISK_USAGE_PERCENT));
        assertTrue(metrics.get("usagePercent") instanceof Double);
    }

    @Test
    void testTotalSpaceIsPositive() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long totalSpace = (Long) metrics.get("totalSpace");
        assertTrue(totalSpace > 0, "Total space should be positive");
    }

    @Test
    void testFreeSpaceIsNonNegative() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long freeSpace = (Long) metrics.get("freeSpace");
        assertTrue(freeSpace >= 0, "Free space should be non-negative");
    }

    @Test
    void testUsedSpaceIsNonNegative() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long usedSpace = (Long) metrics.get("usedSpace");
        assertTrue(usedSpace >= 0, "Used space should be non-negative");
    }

    @Test
    void testUsagePercentIsBetween0And100() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        double usagePercent = (Double) metrics.get("usagePercent");
        assertTrue(usagePercent >= 0.0, "Usage percent should be >= 0");
        assertTrue(usagePercent <= 100.0, "Usage percent should be <= 100");
    }

    @Test
    void testUsedSpaceEqualsTotalMinusFree() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long totalSpace = (Long) metrics.get("totalSpace");
        long freeSpace = (Long) metrics.get("freeSpace");
        long usedSpace = (Long) metrics.get("usedSpace");
        assertEquals(usedSpace, totalSpace - freeSpace, "Used space should equal total minus free");
    }

    @Test
    void testUsagePercentCalculation() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long totalSpace = (Long) metrics.get("totalSpace");
        long usedSpace = (Long) metrics.get("usedSpace");
        double expectedPercent = totalSpace > 0 ? (double) usedSpace / totalSpace * 100 : 0;
        double actualPercent = (Double) metrics.get("usagePercent");
        assertEquals(expectedPercent, actualPercent, 0.01, "Usage percent calculation should match");
    }

    @Test
    void testIsAvailable() {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When & Then - root directory should exist on most systems
        assertTrue(collector.isAvailable());
    }

    @Test
    void testGetCost() {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When & Then
        assertEquals(CollectionCost.LOW, collector.getCost());
    }

    @Test
    void testMetricKeysConsistency() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - verify both old and new key formats exist and have same values
        assertEquals(metrics.get("totalSpace"), metrics.get(MetricKeys.DISK_TOTAL_SPACE));
        assertEquals(metrics.get("freeSpace"), metrics.get(MetricKeys.DISK_FREE_SPACE));
        assertEquals(metrics.get("usedSpace"), metrics.get(MetricKeys.DISK_USED_SPACE));
        assertEquals(metrics.get("usagePercent"), metrics.get(MetricKeys.DISK_USAGE_PERCENT));
    }

    @Test
    void testFreeSpaceLessOrEqualToTotalSpace() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long freeSpace = (Long) metrics.get("freeSpace");
        long totalSpace = (Long) metrics.get("totalSpace");
        assertTrue(freeSpace <= totalSpace, "Free space should be <= total space");
    }

    @Test
    void testUsedSpaceLessOrEqualToTotalSpace() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        long usedSpace = (Long) metrics.get("usedSpace");
        long totalSpace = (Long) metrics.get("totalSpace");
        assertTrue(usedSpace <= totalSpace, "Used space should be <= total space");
    }

    @Test
    void testMultipleCollectionsReturnDifferentValues() throws Exception {
        // Given
        DiskMetricCollector collector = new DiskMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - total space should be constant, but free/used might vary slightly
        assertEquals(metrics1.get("totalSpace"), metrics2.get("totalSpace"));
    }
}
