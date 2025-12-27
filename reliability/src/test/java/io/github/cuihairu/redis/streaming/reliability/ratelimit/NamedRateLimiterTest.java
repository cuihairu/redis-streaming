package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetrics;
import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetricsCollector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class NamedRateLimiterTest {

    @Test
    void delegatesAndEmitsAllowedDeniedMetrics() {
        RecordingCollector collector = new RecordingCollector();
        RateLimitMetrics.setCollector(collector);

        RateLimiter allow = (key, nowMillis) -> true;
        RateLimiter deny = (key, nowMillis) -> false;

        NamedRateLimiter namedAllow = new NamedRateLimiter("limiterA", allow);
        NamedRateLimiter namedDeny = new NamedRateLimiter("limiterB", deny);

        assertTrue(namedAllow.allowAt("k", 1));
        assertFalse(namedDeny.allowAt("k", 1));

        assertEquals(1, collector.allowed.getOrDefault("limiterA", 0));
        assertEquals(0, collector.denied.getOrDefault("limiterA", 0));

        assertEquals(0, collector.allowed.getOrDefault("limiterB", 0));
        assertEquals(1, collector.denied.getOrDefault("limiterB", 0));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new NamedRateLimiter(null, (k, t) -> true));
        assertThrows(NullPointerException.class, () -> new NamedRateLimiter("x", null));
    }

    private static final class RecordingCollector implements RateLimitMetricsCollector {
        private final Map<String, Integer> allowed = new ConcurrentHashMap<>();
        private final Map<String, Integer> denied = new ConcurrentHashMap<>();

        @Override
        public void incAllowed(String name) {
            allowed.merge(name, 1, Integer::sum);
        }

        @Override
        public void incDenied(String name) {
            denied.merge(name, 1, Integer::sum);
        }
    }
}

