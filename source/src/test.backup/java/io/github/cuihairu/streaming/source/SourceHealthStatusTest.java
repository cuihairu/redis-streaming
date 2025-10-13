package io.github.cuihairu.redis.streaming.source;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SourceHealthStatusTest {

    @Test
    void testHealthyStatus() {
        SourceHealthStatus status = SourceHealthStatus.healthy("All systems operational");

        assertEquals(SourceHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("All systems operational", status.getMessage());
        assertNotNull(status.getTimestamp());
        assertTrue(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testUnhealthyStatus() {
        SourceHealthStatus status = SourceHealthStatus.unhealthy("Connection failed");

        assertEquals(SourceHealthStatus.Status.UNHEALTHY, status.getStatus());
        assertEquals("Connection failed", status.getMessage());
        assertFalse(status.isHealthy());
        assertTrue(status.isUnhealthy());
    }

    @Test
    void testDegradedStatus() {
        SourceHealthStatus status = SourceHealthStatus.degraded("Slow response times");

        assertEquals(SourceHealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("Slow response times", status.getMessage());
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testUnknownStatus() {
        SourceHealthStatus status = SourceHealthStatus.unknown("Status not determined");

        assertEquals(SourceHealthStatus.Status.UNKNOWN, status.getStatus());
        assertEquals("Status not determined", status.getMessage());
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testFullConstructor() {
        Instant timestamp = Instant.now();
        SourceHealthStatus status = new SourceHealthStatus(
                SourceHealthStatus.Status.HEALTHY,
                "Test message",
                timestamp,
                100L,
                5L
        );

        assertEquals(SourceHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("Test message", status.getMessage());
        assertEquals(timestamp, status.getTimestamp());
        assertEquals(100L, status.getRecordsPolled());
        assertEquals(5L, status.getErrorsCount());
    }

    @Test
    void testSimpleConstructor() {
        SourceHealthStatus status = new SourceHealthStatus(
                SourceHealthStatus.Status.DEGRADED,
                "Simple test"
        );

        assertEquals(SourceHealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("Simple test", status.getMessage());
        assertNotNull(status.getTimestamp());
        assertEquals(0L, status.getRecordsPolled());
        assertEquals(0L, status.getErrorsCount());
    }
}