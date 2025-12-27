package io.github.cuihairu.redis.streaming.starter.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RateLimitMicrometerCollectorTest {

    @Test
    public void testAllowedDeniedCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitMicrometerCollector c = new RateLimitMicrometerCollector(registry);

        c.incAllowed("rl1");
        c.incAllowed("rl1");
        c.incDenied("rl1");

        assertEquals(2.0, registry.get("redis_streaming_rl_allowed_total").tag("name", "rl1").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_rl_denied_total").tag("name", "rl1").counter().count(), 0.0001);
    }
}

