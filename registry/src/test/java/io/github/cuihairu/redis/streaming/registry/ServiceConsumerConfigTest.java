package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceConsumerConfigTest {

    @Test
    public void testDefaults() {
        ServiceConsumerConfig cfg = new ServiceConsumerConfig();
        assertFalse(cfg.isEnableHealthCheck());
        assertEquals(30, cfg.getHealthCheckInterval());
        assertEquals(java.util.concurrent.TimeUnit.SECONDS, cfg.getHealthCheckTimeUnit());
        assertEquals(5000, cfg.getHealthCheckTimeout());
        assertEquals(90, cfg.getHeartbeatTimeoutSeconds());
        assertTrue(cfg.isEnableAdminService());
    }

    @Test
    public void testKeyHelpers() {
        ServiceConsumerConfig cfg = new ServiceConsumerConfig("pfx");
        assertEquals("pfx:services:svc:instance:ins", cfg.getServiceInstanceKey("svc", "ins"));
        assertEquals("pfx:services:svc:heartbeats", cfg.getServiceInstancesKey("svc"));
        assertEquals("pfx:services:svc:heartbeats", cfg.getHeartbeatKey("svc"));
        assertEquals("pfx:services:svc:changes", cfg.getServiceChangeChannelKey("svc"));
    }
}

