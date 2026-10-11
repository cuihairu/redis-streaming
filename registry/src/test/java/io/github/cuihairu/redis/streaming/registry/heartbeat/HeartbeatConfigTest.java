package io.github.cuihairu.redis.streaming.registry.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
        assertFalse(config.isEnableMetadataChangeDetection());
        assertEquals(600, config.getMetadataUpdateIntervalSeconds());
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
    void testCompleteConfiguration() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When - set all properties
        config.setHeartbeatInterval(Duration.ofSeconds(10));
        config.setEnableMetadataChangeDetection(true);
        config.setMetadataUpdateIntervalSeconds(900);

        // Then - verify all properties
        assertEquals(Duration.ofSeconds(10), config.getHeartbeatInterval());
        assertTrue(config.isEnableMetadataChangeDetection());
        assertEquals(900, config.getMetadataUpdateIntervalSeconds());
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
    void testLargeIntervals() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setHeartbeatInterval(Duration.ofHours(1));

        // Then
        assertEquals(Duration.ofHours(1), config.getHeartbeatInterval());
    }

    @Test
    void testMillisecondIntervals() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // When
        config.setHeartbeatInterval(Duration.ofMillis(500));

        // Then
        assertEquals(Duration.ofMillis(500), config.getHeartbeatInterval());
    }

    @Test
    void testDefaultHeartbeatIntervalIsThreeSeconds() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(3, config.getHeartbeatInterval().getSeconds());
    }

    @Test
    void testDefaultMetadataUpdateIntervalIsTenMinutes() {
        // Given
        HeartbeatConfig config = new HeartbeatConfig();

        // Then
        assertEquals(600, config.getMetadataUpdateIntervalSeconds());
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

        config.setMetadataUpdateIntervalSeconds(300);
        assertEquals(300, config.getMetadataUpdateIntervalSeconds());

        config.setMetadataUpdateIntervalSeconds(900);
        assertEquals(900, config.getMetadataUpdateIntervalSeconds());
    }
}
