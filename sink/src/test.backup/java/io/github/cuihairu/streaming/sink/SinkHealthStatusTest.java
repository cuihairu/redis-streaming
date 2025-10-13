package io.github.cuihairu.redis.streaming.sink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SinkHealthStatusTest {

    @Test
    void testHealthyStatus() {
        SinkHealthStatus status = SinkHealthStatus.healthy("All systems operational");

        assertEquals(SinkHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("All systems operational", status.getMessage());
        assertNotNull(status.getTimestamp());
        assertTrue(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testUnhealthyStatus() {
        SinkHealthStatus status = SinkHealthStatus.unhealthy("Connection failed");

        assertEquals(SinkHealthStatus.Status.UNHEALTHY, status.getStatus());
        assertEquals("Connection failed", status.getMessage());
        assertFalse(status.isHealthy());
        assertTrue(status.isUnhealthy());
    }

    @Test
    void testDegradedStatus() {
        SinkHealthStatus status = SinkHealthStatus.degraded("Slow response times");

        assertEquals(SinkHealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("Slow response times", status.getMessage());
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testUnknownStatus() {
        SinkHealthStatus status = SinkHealthStatus.unknown("Status not determined");

        assertEquals(SinkHealthStatus.Status.UNKNOWN, status.getStatus());
        assertEquals("Status not determined", status.getMessage());
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testFullConstructor() {
        SinkHealthStatus status = new SinkHealthStatus(
                SinkHealthStatus.Status.HEALTHY,
                "Test message",
                java.time.Instant.now(),
                100L,
                5L
        );

        assertEquals(SinkHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("Test message", status.getMessage());
        assertEquals(100L, status.getRecordsWritten());
        assertEquals(5L, status.getErrorsCount());
    }

    @Test
    void testSimpleConstructor() {
        SinkHealthStatus status = new SinkHealthStatus(
                SinkHealthStatus.Status.DEGRADED,
                "Simple test"
        );

        assertEquals(SinkHealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("Simple test", status.getMessage());
        assertNotNull(status.getTimestamp());
        assertEquals(0L, status.getRecordsWritten());
        assertEquals(0L, status.getErrorsCount());
    }
}