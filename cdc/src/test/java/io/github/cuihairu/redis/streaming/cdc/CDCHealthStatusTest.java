package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CDCHealthStatusTest {

    @Test
    void testConstructorWithStatusAndMessage() {
        CDCHealthStatus status = new CDCHealthStatus(
                CDCHealthStatus.Status.HEALTHY,
                "All good"
        );

        assertEquals(CDCHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("All good", status.getMessage());
        assertNotNull(status.getTimestamp());
        assertEquals(0, status.getEventsCaptured());
        assertEquals(0, status.getErrorsCount());
        assertNull(status.getCurrentPosition());
    }

    @Test
    void testFullConstructor() {
        CDCHealthStatus status = new CDCHealthStatus(
                CDCHealthStatus.Status.DEGRADED,
                "Some issues",
                java.time.Instant.now(),
                100,
                5,
                "pos:123"
        );

        assertEquals(CDCHealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("Some issues", status.getMessage());
        assertEquals(100, status.getEventsCaptured());
        assertEquals(5, status.getErrorsCount());
        assertEquals("pos:123", status.getCurrentPosition());
    }

    @Test
    void testHealthyFactory() {
        CDCHealthStatus status = CDCHealthStatus.healthy("Running well");

        assertEquals(CDCHealthStatus.Status.HEALTHY, status.getStatus());
        assertEquals("Running well", status.getMessage());
        assertTrue(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testDegradedFactory() {
        CDCHealthStatus status = CDCHealthStatus.degraded("Performance issues");

        assertEquals(CDCHealthStatus.Status.DEGRADED, status.getStatus());
        assertEquals("Performance issues", status.getMessage());
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testUnhealthyFactory() {
        CDCHealthStatus status = CDCHealthStatus.unhealthy("Connection failed");

        assertEquals(CDCHealthStatus.Status.UNHEALTHY, status.getStatus());
        assertEquals("Connection failed", status.getMessage());
        assertFalse(status.isHealthy());
        assertTrue(status.isUnhealthy());
    }

    @Test
    void testUnknownFactory() {
        CDCHealthStatus status = CDCHealthStatus.unknown("Starting up");

        assertEquals(CDCHealthStatus.Status.UNKNOWN, status.getStatus());
        assertEquals("Starting up", status.getMessage());
        assertFalse(status.isHealthy());
        assertFalse(status.isUnhealthy());
    }

    @Test
    void testStatusEnum() {
        assertEquals(4, CDCHealthStatus.Status.values().length);
        assertNotNull(CDCHealthStatus.Status.valueOf("HEALTHY"));
        assertNotNull(CDCHealthStatus.Status.valueOf("DEGRADED"));
        assertNotNull(CDCHealthStatus.Status.valueOf("UNHEALTHY"));
        assertNotNull(CDCHealthStatus.Status.valueOf("UNKNOWN"));
    }

    @Test
    void testIsHealthyMethod() {
        assertTrue(CDCHealthStatus.healthy("").isHealthy());
        assertFalse(CDCHealthStatus.degraded("").isHealthy());
        assertFalse(CDCHealthStatus.unhealthy("").isHealthy());
        assertFalse(CDCHealthStatus.unknown("").isHealthy());
    }

    @Test
    void testIsUnhealthyMethod() {
        assertFalse(CDCHealthStatus.healthy("").isUnhealthy());
        assertFalse(CDCHealthStatus.degraded("").isUnhealthy());
        assertTrue(CDCHealthStatus.unhealthy("").isUnhealthy());
        assertFalse(CDCHealthStatus.unknown("").isUnhealthy());
    }
}