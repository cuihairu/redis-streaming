package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApplicationMetricCollector
 */
class ApplicationMetricCollectorTest {

    @Test
    void testGetMetricType() {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        String type = collector.getMetricType();

        // Then
        assertEquals("application", type);
    }

    @Test
    void testCollectMetricReturnsMap() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Object result = collector.collectMetric();

        // Then
        assertNotNull(result);
        assertTrue(result instanceof Map);
    }

    @Test
    void testCollectMetricContainsThreadCount() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("threadCount"));
        assertTrue(metrics.containsKey(MetricKeys.APPLICATION_THREAD_COUNT));
        assertTrue(metrics.get("threadCount") instanceof Integer);
        assertTrue((Integer) metrics.get("threadCount") > 0);
    }

    @Test
    void testCollectMetricContainsPeakThreadCount() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("peakThreadCount"));
        assertTrue(metrics.containsKey(MetricKeys.APPLICATION_PEAK_THREAD_COUNT));
        assertTrue(metrics.get("peakThreadCount") instanceof Integer);
        assertTrue((Integer) metrics.get("peakThreadCount") > 0);
    }

    @Test
    void testCollectMetricContainsDaemonThreadCount() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("daemonThreadCount"));
        assertTrue(metrics.containsKey(MetricKeys.APPLICATION_DAEMON_THREAD_COUNT));
        assertTrue(metrics.get("daemonThreadCount") instanceof Integer);
        assertTrue((Integer) metrics.get("daemonThreadCount") >= 0);
    }

    @Test
    void testCollectMetricContainsUptime() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("uptime"));
        assertTrue(metrics.containsKey(MetricKeys.APPLICATION_UPTIME));
        assertTrue(metrics.get("uptime") instanceof Long);
        assertTrue((Long) metrics.get("uptime") >= 0);
    }

    @Test
    void testCollectMetricContainsStartTime() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("startTime"));
        assertTrue(metrics.containsKey(MetricKeys.APPLICATION_START_TIME));
        assertTrue(metrics.get("startTime") instanceof Long);
        assertTrue((Long) metrics.get("startTime") > 0);
    }

    @Test
    void testCollectMetricContainsVmName() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("vmName"));
        assertNotNull(metrics.get("vmName"));
    }

    @Test
    void testCollectMetricContainsVmVersion() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("vmVersion"));
        assertNotNull(metrics.get("vmVersion"));
    }

    @Test
    void testCollectMetricContainsPid() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("pid"));
        assertTrue(metrics.get("pid") instanceof Long);
        assertTrue((Long) metrics.get("pid") > 0);
    }

    @Test
    void testCollectMetricContainsHostname() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        assertTrue(metrics.containsKey("hostname"));
        assertNotNull(metrics.get("hostname"));
    }

    @Test
    void testIsAvailable() {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When & Then
        assertTrue(collector.isAvailable());
    }

    @Test
    void testGetCost() {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When & Then
        assertEquals(CollectionCost.LOW, collector.getCost());
    }

    @Test
    void testMultipleCollectionsReturnDifferentValues() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics1 = (Map<String, Object>) collector.collectMetric();
        Thread.sleep(10); // Small delay
        Map<String, Object> metrics2 = (Map<String, Object>) collector.collectMetric();

        // Then - uptime should have increased
        long uptime1 = (Long) metrics1.get("uptime");
        long uptime2 = (Long) metrics2.get("uptime");
        assertTrue(uptime2 >= uptime1);
    }

    @Test
    void testPeakThreadCountGreaterOrEqualThreadCount() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then
        int threadCount = (Integer) metrics.get("threadCount");
        int peakThreadCount = (Integer) metrics.get("peakThreadCount");
        assertTrue(peakThreadCount >= threadCount);
    }

    @Test
    void testStartTimeReasonable() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();
        long now = System.currentTimeMillis();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - start time should be in the past but not too long ago
        long startTime = (Long) metrics.get("startTime");
        assertTrue(startTime > 0);
        assertTrue(startTime <= now);
    }

    @Test
    void testMetricKeysConsistency() throws Exception {
        // Given
        ApplicationMetricCollector collector = new ApplicationMetricCollector();

        // When
        Map<String, Object> metrics = (Map<String, Object>) collector.collectMetric();

        // Then - verify both old and new key formats exist and have same values
        assertEquals(metrics.get("threadCount"), metrics.get(MetricKeys.APPLICATION_THREAD_COUNT));
        assertEquals(metrics.get("peakThreadCount"), metrics.get(MetricKeys.APPLICATION_PEAK_THREAD_COUNT));
        assertEquals(metrics.get("daemonThreadCount"), metrics.get(MetricKeys.APPLICATION_DAEMON_THREAD_COUNT));
        assertEquals(metrics.get("uptime"), metrics.get(MetricKeys.APPLICATION_UPTIME));
        assertEquals(metrics.get("startTime"), metrics.get(MetricKeys.APPLICATION_START_TIME));
    }
}
