package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CDCHealthStatus
 */
class CDCHealthStatusTest {

    @Test
    void testAllArgsConstructor() {
        // Given
        CDCHealthStatus.Status status = CDCHealthStatus.Status.HEALTHY;
        String message = "All systems operational";
        Instant timestamp = Instant.now();
        long eventsCaptured = 1000;
        long errorsCount = 5;
        String currentPosition = "binlog.000123:456";

        // When
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            status, message, timestamp, eventsCaptured, errorsCount, currentPosition
        );

        // Then
        assertEquals(status, healthStatus.getStatus());
        assertEquals(message, healthStatus.getMessage());
        assertEquals(timestamp, healthStatus.getTimestamp());
        assertEquals(eventsCaptured, healthStatus.getEventsCaptured());
        assertEquals(errorsCount, healthStatus.getErrorsCount());
        assertEquals(currentPosition, healthStatus.getCurrentPosition());
    }

    @Test
    void testSimplifiedConstructor() {
        // Given
        CDCHealthStatus.Status status = CDCHealthStatus.Status.HEALTHY;
        String message = "Running smoothly";

        // When
        CDCHealthStatus healthStatus = new CDCHealthStatus(status, message);

        // Then
        assertEquals(status, healthStatus.getStatus());
        assertEquals(message, healthStatus.getMessage());
        assertNotNull(healthStatus.getTimestamp());
        assertEquals(0, healthStatus.getEventsCaptured());
        assertEquals(0, healthStatus.getErrorsCount());
        assertNull(healthStatus.getCurrentPosition());
    }

    @Test
    void testHealthyFactory() {
        // Given
        String message = "Connector is healthy";

        // When
        CDCHealthStatus healthStatus = CDCHealthStatus.healthy(message);

        // Then
        assertEquals(CDCHealthStatus.Status.HEALTHY, healthStatus.getStatus());
        assertEquals(message, healthStatus.getMessage());
        assertTrue(healthStatus.isHealthy());
    }

    @Test
    void testDegradedFactory() {
        // Given
        String message = "Connector is degraded";

        // When
        CDCHealthStatus healthStatus = CDCHealthStatus.degraded(message);

        // Then
        assertEquals(CDCHealthStatus.Status.DEGRADED, healthStatus.getStatus());
        assertEquals(message, healthStatus.getMessage());
        assertFalse(healthStatus.isHealthy());
    }

    @Test
    void testUnhealthyFactory() {
        // Given
        String message = "Connector is unhealthy";

        // When
        CDCHealthStatus healthStatus = CDCHealthStatus.unhealthy(message);

        // Then
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, healthStatus.getStatus());
        assertEquals(message, healthStatus.getMessage());
        assertTrue(healthStatus.isUnhealthy());
    }

    @Test
    void testUnknownFactory() {
        // Given
        String message = "Connector status unknown";

        // When
        CDCHealthStatus healthStatus = CDCHealthStatus.unknown(message);

        // Then
        assertEquals(CDCHealthStatus.Status.UNKNOWN, healthStatus.getStatus());
        assertEquals(message, healthStatus.getMessage());
        assertFalse(healthStatus.isHealthy());
        assertFalse(healthStatus.isUnhealthy());
    }

    @Test
    void testIsHealthyWithHealthyStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.HEALTHY, "OK", Instant.now(), 0, 0, null
        );

        // When & Then
        assertTrue(healthStatus.isHealthy());
    }

    @Test
    void testIsHealthyWithDegradedStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.DEGRADED, "Slow", Instant.now(), 0, 0, null
        );

        // When & Then
        assertFalse(healthStatus.isHealthy());
    }

    @Test
    void testIsHealthyWithUnhealthyStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.UNHEALTHY, "Error", Instant.now(), 0, 0, null
        );

        // When & Then
        assertFalse(healthStatus.isHealthy());
    }

    @Test
    void testIsHealthyWithUnknownStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.UNKNOWN, "Unknown", Instant.now(), 0, 0, null
        );

        // When & Then
        assertFalse(healthStatus.isHealthy());
    }

    @Test
    void testIsUnhealthyWithUnhealthyStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.UNHEALTHY, "Error", Instant.now(), 0, 0, null
        );

        // When & Then
        assertTrue(healthStatus.isUnhealthy());
    }

    @Test
    void testIsUnhealthyWithHealthyStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.HEALTHY, "OK", Instant.now(), 0, 0, null
        );

        // When & Then
        assertFalse(healthStatus.isUnhealthy());
    }

    @Test
    void testIsUnhealthyWithDegradedStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.DEGRADED, "Slow", Instant.now(), 0, 0, null
        );

        // When & Then
        assertFalse(healthStatus.isUnhealthy());
    }

    @Test
    void testIsUnhealthyWithUnknownStatus() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.UNKNOWN, "Unknown", Instant.now(), 0, 0, null
        );

        // When & Then
        assertFalse(healthStatus.isUnhealthy());
    }

    @Test
    void testStatusEnumValues() {
        // Given & When
        CDCHealthStatus.Status[] values = CDCHealthStatus.Status.values();

        // Then
        assertEquals(4, values.length);
        assertEquals(CDCHealthStatus.Status.HEALTHY, values[0]);
        assertEquals(CDCHealthStatus.Status.DEGRADED, values[1]);
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, values[2]);
        assertEquals(CDCHealthStatus.Status.UNKNOWN, values[3]);
    }

    @Test
    void testStatusValueOf() {
        // Given & When
        CDCHealthStatus.Status healthy = CDCHealthStatus.Status.valueOf("HEALTHY");
        CDCHealthStatus.Status degraded = CDCHealthStatus.Status.valueOf("DEGRADED");
        CDCHealthStatus.Status unhealthy = CDCHealthStatus.Status.valueOf("UNHEALTHY");
        CDCHealthStatus.Status unknown = CDCHealthStatus.Status.valueOf("UNKNOWN");

        // Then
        assertEquals(CDCHealthStatus.Status.HEALTHY, healthy);
        assertEquals(CDCHealthStatus.Status.DEGRADED, degraded);
        assertEquals(CDCHealthStatus.Status.UNHEALTHY, unhealthy);
        assertEquals(CDCHealthStatus.Status.UNKNOWN, unknown);
    }

    @Test
    void testGetters() {
        // Given
        Instant timestamp = Instant.now();
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.HEALTHY, "OK", timestamp, 100, 2, "pos:123"
        );

        // When & Then
        assertEquals(CDCHealthStatus.Status.HEALTHY, healthStatus.getStatus());
        assertEquals("OK", healthStatus.getMessage());
        assertEquals(timestamp, healthStatus.getTimestamp());
        assertEquals(100, healthStatus.getEventsCaptured());
        assertEquals(2, healthStatus.getErrorsCount());
        assertEquals("pos:123", healthStatus.getCurrentPosition());
    }

    @Test
    void testWithLargeNumbers() {
        // Given
        long largeEventsCount = Long.MAX_VALUE;
        long largeErrorsCount = 1000000L;

        // When
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.HEALTHY, "OK", Instant.now(), 
            largeEventsCount, largeErrorsCount, "pos:456"
        );

        // Then
        assertEquals(largeEventsCount, healthStatus.getEventsCaptured());
        assertEquals(largeErrorsCount, healthStatus.getErrorsCount());
    }

    @Test
    void testWithNullCurrentPosition() {
        // Given
        CDCHealthStatus healthStatus = new CDCHealthStatus(
            CDCHealthStatus.Status.HEALTHY, "OK", Instant.now(), 100, 0, null
        );

        // When & Then
        assertNull(healthStatus.getCurrentPosition());
    }

    @Test
    void testWithEmptyMessage() {
        // Given
        String emptyMessage = "";

        // When
        CDCHealthStatus healthStatus = CDCHealthStatus.healthy(emptyMessage);

        // Then
        assertEquals(emptyMessage, healthStatus.getMessage());
    }

    @Test
    void testMultipleStatusInstances() {
        // Given
        CDCHealthStatus healthy = CDCHealthStatus.healthy("OK");
        CDCHealthStatus degraded = CDCHealthStatus.degraded("Slow");
        CDCHealthStatus unhealthy = CDCHealthStatus.unhealthy("Error");
        CDCHealthStatus unknown = CDCHealthStatus.unknown("Unknown");

        // When & Then - each should have different status
        assertNotEquals(healthy.getStatus(), degraded.getStatus());
        assertNotEquals(healthy.getStatus(), unhealthy.getStatus());
        assertNotEquals(healthy.getStatus(), unknown.getStatus());
        assertNotEquals(degraded.getStatus(), unhealthy.getStatus());
        assertNotEquals(degraded.getStatus(), unknown.getStatus());
        assertNotEquals(unhealthy.getStatus(), unknown.getStatus());
    }
}
