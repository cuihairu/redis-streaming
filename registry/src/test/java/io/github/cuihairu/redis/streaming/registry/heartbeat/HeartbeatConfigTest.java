package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HeartbeatConfig
 */
class HeartbeatConfigTest {

    @Test
    void testDefaultValues() {
        // Given & When
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(Duration.ofSeconds(3), config.getHeartbeatInterval());
        assertEquals(Duration.ofSeconds(60), config.getMetricsInterval());
        assertFalse(config.isEnableMetadataChangeDetection());
        assertEquals(600, config.getMetadataUpdateIntervalSeconds());
        assertTrue(config.isForceUpdateOnHealthChange());
        assertTrue(config.isForceUpdateOnStartup());
        assertEquals(20, config.getForceMetricsUpdateThreshold());
    }

    @Test
    void testSetHeartbeatInterval() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setHeartbeatInterval(Duration.ofSeconds(5));

        // Then
        assertEquals(Duration.ofSeconds(5), config.getHeartbeatInterval());
    }

    @Test
    void testSetMetricsInterval() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setMetricsInterval(Duration.ofSeconds(120));

        // Then
        assertEquals(Duration.ofSeconds(120), config.getMetricsInterval());
    }

    @Test
    void testGetMetricsUpdateIntervalMs() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();
        config.setMetricsInterval(Duration.ofMillis(1500));

        // When
        long ms = config.getMetricsUpdateIntervalMs();

        // Then
        assertEquals(1500, ms);
    }

    @Test
    void testDefaultChangeThresholds() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        Map<String, ChangeThreshold> thresholds = config.getChangeThresholds();

        // Then - verify all default thresholds exist
        assertTrue(thresholds.containsKey(MetricKeys.MEMORY_HEAP_USAGE_PERCENT));
        assertTrue(thresholds.containsKey(MetricKeys.CPU_PROCESS_LOAD));
        assertTrue(thresholds.containsKey(MetricKeys.DISK_USAGE_PERCENT));
        assertTrue(thresholds.containsKey(MetricKeys.APPLICATION_THREAD_COUNT));
        assertTrue(thresholds.containsKey(MetricKeys.HEALTHY));

        // Verify specific threshold values
        ChangeThreshold memoryThreshold = thresholds.get(MetricKeys.MEMORY_HEAP_USAGE_PERCENT);
        assertEquals(10.0, memoryThreshold.getThreshold());
        assertEquals(ChangeThresholdType.ABSOLUTE, memoryThreshold.getType());

        ChangeThreshold cpuThreshold = thresholds.get(MetricKeys.CPU_PROCESS_LOAD);
        assertEquals(0.20, cpuThreshold.getThreshold());
        assertEquals(ChangeThresholdType.ABSOLUTE, cpuThreshold.getType());

        ChangeThreshold healthyThreshold = thresholds.get(MetricKeys.HEALTHY);
        assertEquals(0, healthyThreshold.getThreshold());
        assertEquals(ChangeThresholdType.ANY, healthyThreshold.getType());
    }

    @Test
    void testSetCustomChangeThresholds() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();
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
    void testSetEnableMetadataChangeDetection() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setEnableMetadataChangeDetection(true);

        // Then
        assertTrue(config.isEnableMetadataChangeDetection());
    }

    @Test
    void testSetMetadataUpdateIntervalSeconds() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setMetadataUpdateIntervalSeconds(300);

        // Then
        assertEquals(300, config.getMetadataUpdateIntervalSeconds());
    }

    @Test
    void testSetForceUpdateOnHealthChange() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setForceUpdateOnHealthChange(false);

        // Then
        assertFalse(config.isForceUpdateOnHealthChange());
    }

    @Test
    void testSetForceUpdateOnStartup() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setForceUpdateOnStartup(false);

        // Then
        assertFalse(config.isForceUpdateOnStartup());
    }

    @Test
    void testSetForceMetricsUpdateThreshold() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setForceMetricsUpdateThreshold(50);

        // Then
        assertEquals(50, config.getForceMetricsUpdateThreshold());
    }

    @Test
    void testGetForceUpdateThreshold() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();
        config.setForceMetricsUpdateThreshold(30);

        // When
        int threshold = config.getForceUpdateThreshold();

        // Then
        assertEquals(30, threshold);
    }

    @Test
    void testCompleteConfiguration() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When - set all properties
        config.setHeartbeatInterval(Duration.ofSeconds(10));
        config.setMetricsInterval(Duration.ofSeconds(90));
        config.setEnableMetadataChangeDetection(true);
        config.setMetadataUpdateIntervalSeconds(900);
        config.setForceUpdateOnHealthChange(false);
        config.setForceUpdateOnStartup(false);
        config.setForceMetricsUpdateThreshold(40);

        // Then - verify all properties
        assertEquals(Duration.ofSeconds(10), config.getHeartbeatInterval());
        assertEquals(Duration.ofSeconds(90), config.getMetricsInterval());
        assertTrue(config.isEnableMetadataChangeDetection());
        assertEquals(900, config.getMetadataUpdateIntervalSeconds());
        assertFalse(config.isForceUpdateOnHealthChange());
        assertFalse(config.isForceUpdateOnStartup());
        assertEquals(40, config.getForceMetricsUpdateThreshold());
        assertEquals(40, config.getForceUpdateThreshold());
    }

    @Test
    void testZeroMetadataUpdateInterval() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setMetadataUpdateIntervalSeconds(0);

        // Then
        assertEquals(0, config.getMetadataUpdateIntervalSeconds());
    }

    @Test
    void testZeroForceMetricsUpdateThreshold() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setForceMetricsUpdateThreshold(0);

        // Then
        assertEquals(0, config.getForceMetricsUpdateThreshold());
    }

    @Test
    void testLargeIntervals() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setHeartbeatInterval(Duration.ofHours(1));
        config.setMetricsInterval(Duration.ofHours(2));

        // Then
        assertEquals(Duration.ofHours(1), config.getHeartbeatInterval());
        assertEquals(Duration.ofHours(2), config.getMetricsInterval());
        assertEquals(7200000, config.getMetricsUpdateIntervalMs()); // 2 hours in ms
    }

    @Test
    void testMillisecondIntervals() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setHeartbeatInterval(Duration.ofMillis(500));
        config.setMetricsInterval(Duration.ofMillis(1000));

        // Then
        assertEquals(Duration.ofMillis(500), config.getHeartbeatInterval());
        assertEquals(Duration.ofMillis(1000), config.getMetricsInterval());
        assertEquals(1000, config.getMetricsUpdateIntervalMs());
    }

    @Test
    void testImmutableDefaultThresholdsMap() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When - get the default thresholds
        Map<String, ChangeThreshold> thresholds = config.getChangeThresholds();

        // Then - the default map should have 5 entries
        assertEquals(5, thresholds.size());
    }

    @Test
    void testAllDefaultThresholdTypes() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        Map<String, ChangeThreshold> thresholds = config.getChangeThresholds();

        // Then - verify ABSOLUTE is the default type for most metrics
        assertEquals(ChangeThresholdType.ABSOLUTE,
                thresholds.get(MetricKeys.MEMORY_HEAP_USAGE_PERCENT).getType());
        assertEquals(ChangeThresholdType.ABSOLUTE,
                thresholds.get(MetricKeys.CPU_PROCESS_LOAD).getType());
        assertEquals(ChangeThresholdType.ABSOLUTE,
                thresholds.get(MetricKeys.DISK_USAGE_PERCENT).getType());
        assertEquals(ChangeThresholdType.ABSOLUTE,
                thresholds.get(MetricKeys.APPLICATION_THREAD_COUNT).getType());

        // Except HEALTHY which uses ANY
        assertEquals(ChangeThresholdType.ANY,
                thresholds.get(MetricKeys.HEALTHY).getType());
    }

    @Test
    void testPercentageThresholdType() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();
        Map<String, ChangeThreshold> thresholds = Map.of(
                "test.metric", new ChangeThreshold(15.0, ChangeThresholdType.PERCENTAGE)
        );

        // When
        config.setChangeThresholds(thresholds);

        // Then
        assertEquals(ChangeThresholdType.PERCENTAGE,
                config.getChangeThresholds().get("test.metric").getType());
        assertEquals(15.0,
                config.getChangeThresholds().get("test.metric").getThreshold());
    }

    @Test
    void testAnyThresholdType() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();
        Map<String, ChangeThreshold> thresholds = Map.of(
                "any.change", new ChangeThreshold(0, ChangeThresholdType.ANY)
        );

        // When
        config.setChangeThresholds(thresholds);

        // Then
        assertEquals(ChangeThresholdType.ANY,
                config.getChangeThresholds().get("any.change").getType());
        assertEquals(0,
                config.getChangeThresholds().get("any.change").getThreshold());
    }

    @Test
    void testDefaultHeartbeatIntervalIsThreeSeconds() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(3, config.getHeartbeatInterval().getSeconds());
    }

    @Test
    void testDefaultMetricsIntervalIsSixtySeconds() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(60, config.getMetricsInterval().getSeconds());
    }

    @Test
    void testDefaultMetadataUpdateIntervalIsTenMinutes() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(600, config.getMetadataUpdateIntervalSeconds());
    }

    @Test
    void testDefaultForceUpdateThresholdIsTwenty() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(20, config.getForceUpdateThreshold());
    }

    @Test
    void testForceUpdateOnHealthChangeIsTrueByDefault() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertTrue(config.isForceUpdateOnHealthChange());
    }

    @Test
    void testForceUpdateOnStartupIsTrueByDefault() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertTrue(config.isForceUpdateOnStartup());
    }

    @Test
    void testEnableMetadataChangeDetectionIsFalseByDefault() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertFalse(config.isEnableMetadataChangeDetection());
    }

    @Test
    void testMultipleSettersAndQueries() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When - modify multiple times
        config.setHeartbeatInterval(Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(5), config.getHeartbeatInterval());

        config.setHeartbeatInterval(Duration.ofSeconds(8));
        assertEquals(Duration.ofSeconds(8), config.getHeartbeatInterval());

        config.setForceMetricsUpdateThreshold(10);
        assertEquals(10, config.getForceUpdateThreshold());

        config.setForceMetricsUpdateThreshold(25);
        assertEquals(25, config.getForceUpdateThreshold());
    }
}
