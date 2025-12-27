package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NamingServiceConfigTest {

    @Test
    public void testDefaults() {
        NamingServiceConfig cfg = new NamingServiceConfig();
        assertFalse(cfg.isEnableHealthCheck());
        assertEquals(30, cfg.getHealthCheckInterval());
        assertEquals(java.util.concurrent.TimeUnit.SECONDS, cfg.getHealthCheckTimeUnit());
        assertEquals(5000, cfg.getHealthCheckTimeout());
        assertTrue(cfg.isEnableAdminService());
    }

    @Test
    public void testKeyBuildersUseRegistryKeysPrefix() {
        NamingServiceConfig cfg = new NamingServiceConfig("pfx");
        assertEquals("pfx:services:svc:instance:ins", cfg.getServiceInstanceKey("svc", "ins"));
        assertEquals("pfx:services:svc:heartbeats", cfg.getServiceInstancesKey("svc"));
        assertEquals("pfx:services:svc:heartbeats", cfg.getHeartbeatKey("svc"));
        assertEquals("pfx:services:svc:changes", cfg.getServiceChangeChannelKey("svc"));
    }

    @Test
    public void testInvalidServiceNameThrows() {
        NamingServiceConfig cfg = new NamingServiceConfig("pfx");
        assertThrows(IllegalArgumentException.class, () -> cfg.getServiceInstanceKey("bad:svc", "ins"));
    }
}

