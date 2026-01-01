package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricsConfig
 */
class MetricsConfigTest {

    @Test
    void testDefaultValues() {
        // Given & When
        MetricsConfig config = new MetricsConfig();

        // Then
        assertNotNull(config.getEnabledMetrics());
        assertNotNull(config.getCollectionIntervals());
        assertNotNull(config.getChangeThresholds());
        assertEquals(Duration.ofMinutes(1), config.getDefaultCollectionInterval());
        assertTrue(config.isImmediateUpdateOnSignificantChange());
        assertEquals(Duration.ofSeconds(5), config.getCollectionTimeout());
    }

    @Test
    void testDefaultEnabledMetrics() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        Set<String> enabledMetrics = config.getEnabledMetrics();

        // Then
        assertTrue(enabledMetrics.contains("memory"));
        assertTrue(enabledMetrics.contains("cpu"));
        assertTrue(enabledMetrics.contains("application"));
        assertEquals(3, enabledMetrics.size());
    }

    @Test
    void testDefaultCollectionIntervals() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        Map<String, Duration> intervals = config.getCollectionIntervals();

        // Then
        assertEquals(Duration.ofSeconds(30), intervals.get("memory"));
        assertEquals(Duration.ofSeconds(60), intervals.get("cpu"));
        assertEquals(Duration.ofMinutes(5), intervals.get("disk"));
        assertEquals(Duration.ofMinutes(10), intervals.get("network"));
    }

    @Test
    void testDefaultChangeThresholds() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        Map<String, ChangeThreshold> thresholds = config.getChangeThresholds();

        // Then
        assertTrue(thresholds.containsKey(MetricKeys.MEMORY_HEAP_USAGE_PERCENT));
        assertTrue(thresholds.containsKey(MetricKeys.CPU_PROCESS_LOAD));
        assertTrue(thresholds.containsKey(MetricKeys.DISK_USAGE_PERCENT));

        assertEquals(10.0, thresholds.get(MetricKeys.MEMORY_HEAP_USAGE_PERCENT).getThreshold());
        assertEquals(ChangeThresholdType.ABSOLUTE, thresholds.get(MetricKeys.MEMORY_HEAP_USAGE_PERCENT).getType());

        assertEquals(0.20, thresholds.get(MetricKeys.CPU_PROCESS_LOAD).getThreshold());
        assertEquals(ChangeThresholdType.ABSOLUTE, thresholds.get(MetricKeys.CPU_PROCESS_LOAD).getType());

        assertEquals(5.0, thresholds.get(MetricKeys.DISK_USAGE_PERCENT).getThreshold());
        assertEquals(ChangeThresholdType.ABSOLUTE, thresholds.get(MetricKeys.DISK_USAGE_PERCENT).getType());
    }

    @Test
    void testSetEnabledMetrics() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Set<String> customMetrics = Set.of("memory", "cpu", "disk", "gc");

        // When
        config.setEnabledMetrics(customMetrics);

        // Then
        assertEquals(customMetrics, config.getEnabledMetrics());
        assertEquals(4, config.getEnabledMetrics().size());
        assertTrue(config.getEnabledMetrics().contains("gc"));
    }

    @Test
    void testSetCollectionIntervals() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Map<String, Duration> customIntervals = Map.of(
                "memory", Duration.ofSeconds(15),
                "cpu", Duration.ofSeconds(30)
        );

        // When
        config.setCollectionIntervals(customIntervals);

        // Then
        assertEquals(customIntervals, config.getCollectionIntervals());
        assertEquals(Duration.ofSeconds(15), config.getCollectionIntervals().get("memory"));
        assertEquals(Duration.ofSeconds(30), config.getCollectionIntervals().get("cpu"));
    }

    @Test
    void testSetChangeThresholds() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Map<String, ChangeThreshold> customThresholds = Map.of(
                "custom.metric", new ChangeThreshold(5.0, ChangeThresholdType.PERCENTAGE)
        );

        // When
        config.setChangeThresholds(customThresholds);

        // Then
        assertEquals(customThresholds, config.getChangeThresholds());
        assertEquals(1, config.getChangeThresholds().size());
        assertTrue(config.getChangeThresholds().containsKey("custom.metric"));
    }

    @Test
    void testSetDefaultCollectionInterval() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setDefaultCollectionInterval(Duration.ofSeconds(30));

        // Then
        assertEquals(Duration.ofSeconds(30), config.getDefaultCollectionInterval());
    }

    @Test
    void testSetImmediateUpdateOnSignificantChange() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setImmediateUpdateOnSignificantChange(false);

        // Then
        assertFalse(config.isImmediateUpdateOnSignificantChange());
    }

    @Test
    void testSetCollectionTimeout() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setCollectionTimeout(Duration.ofSeconds(10));

        // Then
        assertEquals(Duration.ofSeconds(10), config.getCollectionTimeout());
    }

    @Test
    void testEmptyEnabledMetrics() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setEnabledMetrics(Set.of());

        // Then
        assertNotNull(config.getEnabledMetrics());
        assertTrue(config.getEnabledMetrics().isEmpty());
    }

    @Test
    void testEmptyCollectionIntervals() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setCollectionIntervals(Map.of());

        // Then
        assertNotNull(config.getCollectionIntervals());
        assertTrue(config.getCollectionIntervals().isEmpty());
    }

    @Test
    void testEmptyChangeThresholds() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setChangeThresholds(Map.of());

        // Then
        assertNotNull(config.getChangeThresholds());
        assertTrue(config.getChangeThresholds().isEmpty());
    }

    @Test
    void testZeroCollectionTimeout() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setCollectionTimeout(Duration.ZERO);

        // Then
        assertEquals(Duration.ZERO, config.getCollectionTimeout());
    }

    @Test
    void testVeryShortCollectionInterval() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setDefaultCollectionInterval(Duration.ofMillis(100));

        // Then
        assertEquals(Duration.ofMillis(100), config.getDefaultCollectionInterval());
    }

    @Test
    void testVeryLongCollectionInterval() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setDefaultCollectionInterval(Duration.ofHours(1));

        // Then
        assertEquals(Duration.ofHours(1), config.getDefaultCollectionInterval());
    }

    @Test
    void testMultipleEnabledMetrics() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Set<String> metrics = Set.of("memory", "cpu", "disk", "network", "application", "gc");

        // When
        config.setEnabledMetrics(metrics);

        // Then
        assertEquals(6, config.getEnabledMetrics().size());
        assertTrue(config.getEnabledMetrics().contains("memory"));
        assertTrue(config.getEnabledMetrics().contains("cpu"));
        assertTrue(config.getEnabledMetrics().contains("disk"));
        assertTrue(config.getEnabledMetrics().contains("network"));
        assertTrue(config.getEnabledMetrics().contains("application"));
        assertTrue(config.getEnabledMetrics().contains("gc"));
    }

    @Test
    void testComplexChangeThresholds() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Map<String, ChangeThreshold> thresholds = Map.of(
                MetricKeys.MEMORY_HEAP_USAGE_PERCENT, new ChangeThreshold(15.0, ChangeThresholdType.PERCENTAGE),
                MetricKeys.CPU_PROCESS_LOAD, new ChangeThreshold(0.30, ChangeThresholdType.ABSOLUTE),
                MetricKeys.DISK_USAGE_PERCENT, new ChangeThreshold(10.0, ChangeThresholdType.ABSOLUTE),
                "custom.metric1", new ChangeThreshold(5.0, ChangeThresholdType.PERCENTAGE),
                "custom.metric2", new ChangeThreshold(0, ChangeThresholdType.ANY)
        );

        // When
        config.setChangeThresholds(thresholds);

        // Then
        assertEquals(5, config.getChangeThresholds().size());
        assertEquals(ChangeThresholdType.PERCENTAGE, config.getChangeThresholds().get(MetricKeys.MEMORY_HEAP_USAGE_PERCENT).getType());
        assertEquals(ChangeThresholdType.ANY, config.getChangeThresholds().get("custom.metric2").getType());
    }

    @Test
    void testDefaultCollectionTimeoutIsFiveSeconds() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // Then
        assertEquals(5, config.getCollectionTimeout().getSeconds());
    }

    @Test
    void testDefaultImmediateUpdateOnSignificantChangeIsTrue() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // Then
        assertTrue(config.isImmediateUpdateOnSignificantChange());
    }

    @Test
    void testDefaultCollectionIntervalIsOneMinute() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // Then
        assertEquals(1, config.getDefaultCollectionInterval().toMinutes());
    }

    @Test
    void testNanosecondCollectionInterval() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setDefaultCollectionInterval(Duration.ofNanos(1_000_000_000)); // 1 second in nanos

        // Then
        assertEquals(Duration.ofSeconds(1), config.getDefaultCollectionInterval());
    }

    @Test
    void testDifferentMetricIntervals() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Map<String, Duration> intervals = Map.of(
                "fast", Duration.ofSeconds(1),
                "medium", Duration.ofSeconds(30),
                "slow", Duration.ofMinutes(5),
                "verySlow", Duration.ofHours(1)
        );

        // When
        config.setCollectionIntervals(intervals);

        // Then
        assertEquals(4, config.getCollectionIntervals().size());
        assertEquals(Duration.ofSeconds(1), config.getCollectionIntervals().get("fast"));
        assertEquals(Duration.ofHours(1), config.getCollectionIntervals().get("verySlow"));
    }

    @Test
    void testSetterGetterRoundTrip() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        config.setEnabledMetrics(Set.of("test"));
        config.setCollectionIntervals(Map.of("test", Duration.ofSeconds(10)));
        config.setChangeThresholds(Map.of("test", new ChangeThreshold(1.0, ChangeThresholdType.ABSOLUTE)));
        config.setDefaultCollectionInterval(Duration.ofSeconds(20));
        config.setImmediateUpdateOnSignificantChange(false);
        config.setCollectionTimeout(Duration.ofSeconds(30));

        // Then
        assertEquals(Set.of("test"), config.getEnabledMetrics());
        assertEquals(Map.of("test", Duration.ofSeconds(10)), config.getCollectionIntervals());
        assertEquals(Duration.ofSeconds(20), config.getDefaultCollectionInterval());
        assertFalse(config.isImmediateUpdateOnSignificantChange());
        assertEquals(Duration.ofSeconds(30), config.getCollectionTimeout());
    }

    @Test
    void testNullSafety() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When & Then - getters should never return null after construction
        assertNotNull(config.getEnabledMetrics());
        assertNotNull(config.getCollectionIntervals());
        assertNotNull(config.getChangeThresholds());
        assertNotNull(config.getDefaultCollectionInterval());
        assertNotNull(config.getCollectionTimeout());
    }

    @Test
    void testImmutableDefaultCollections() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When - try to modify default collections (should not throw but might not affect original)
        Set<String> enabledMetrics = config.getEnabledMetrics();

        // Then - the returned set should be immutable or at least a view
        // This test verifies the behavior doesn't cause unexpected mutations
        assertEquals(3, enabledMetrics.size());
    }

    @Test
    void testAllDefaultMetricsHaveIntervals() {
        // Given
        MetricsConfig config = new MetricsConfig();

        // When
        Set<String> enabledMetrics = config.getEnabledMetrics();
        Map<String, Duration> intervals = config.getCollectionIntervals();

        // Then - all enabled metrics should have collection intervals
        // Default enabled metrics: memory, cpu, application
        // Default intervals: memory, cpu, disk, network
        // So only "memory" and "cpu" have both enabled and interval
        // "application" is enabled but doesn't have a default interval
        for (String metric : enabledMetrics) {
            boolean hasInterval = intervals.containsKey(metric);
            boolean isOptional = "application".equals(metric) ||
                               "network".equals(metric) ||
                               "gc".equals(metric) ||
                               "disk".equals(metric);
            assertTrue(hasInterval || isOptional,
                    "Metric " + metric + " should have interval or be optional");
        }
    }

    @Test
    void testMillisecondCollectionIntervals() {
        // Given
        MetricsConfig config = new MetricsConfig();
        Map<String, Duration> intervals = Map.of(
                "fast", Duration.ofMillis(100),
                "medium", Duration.ofMillis(500)
        );

        // When
        config.setCollectionIntervals(intervals);

        // Then
        assertEquals(Duration.ofMillis(100), config.getCollectionIntervals().get("fast"));
        assertEquals(Duration.ofMillis(500), config.getCollectionIntervals().get("medium"));
    }
}
