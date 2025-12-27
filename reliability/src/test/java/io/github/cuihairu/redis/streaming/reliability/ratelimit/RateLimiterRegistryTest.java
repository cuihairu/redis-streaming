package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterRegistryTest {

    @Test
    void constructorRejectsNullMap() {
        assertThrows(NullPointerException.class, () -> new RateLimiterRegistry(null));
    }

    @Test
    void registryDefensivelyCopiesAndExposesImmutableViews() {
        RateLimiter limiter = (key, nowMillis) -> true;
        Map<String, RateLimiter> input = new HashMap<>();
        input.put("a", limiter);

        RateLimiterRegistry registry = new RateLimiterRegistry(input);
        assertSame(limiter, registry.get("a"));
        assertNull(registry.get("missing"));

        input.put("b", (k, t) -> false);
        assertNull(registry.get("b"));

        assertThrows(UnsupportedOperationException.class, () -> registry.all().put("x", limiter));
    }
}

