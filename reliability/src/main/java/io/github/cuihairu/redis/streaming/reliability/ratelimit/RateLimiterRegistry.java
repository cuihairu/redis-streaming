package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for named rate limiters.
 */
public class RateLimiterRegistry {
    private final Map<String, RateLimiter> limiters;

    public RateLimiterRegistry(Map<String, RateLimiter> limiters) {
        this.limiters = Map.copyOf(Objects.requireNonNull(limiters, "limiters"));
    }

    /** Get a named rate limiter or null if absent. */
    public RateLimiter get(String name) {
        return limiters.get(name);
    }

    /** All registered rate limiters (immutable). */
    public Map<String, RateLimiter> all() {
        return Collections.unmodifiableMap(limiters);
    }
}

