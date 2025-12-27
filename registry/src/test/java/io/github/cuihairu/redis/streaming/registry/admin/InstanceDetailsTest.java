package io.github.cuihairu.redis.streaming.registry.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InstanceDetailsTest {

    @Test
    public void testHeartbeatDelayAndExpired() {
        InstanceDetails d = new InstanceDetails("svc", "i1");
        d.setLastHeartbeatTime(System.currentTimeMillis() - 1000);
        assertTrue(d.getHeartbeatDelay() >= 900);
        assertTrue(d.isExpired(100));
        assertFalse(d.isExpired(10_000));
    }
}

