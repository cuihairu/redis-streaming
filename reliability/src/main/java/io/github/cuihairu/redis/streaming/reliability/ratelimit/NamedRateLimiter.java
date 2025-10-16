package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetrics;

import java.util.Objects;

/** Decorator that tags a RateLimiter with a name and emits metrics. */
public class NamedRateLimiter implements RateLimiter {
    private final String name;
    private final RateLimiter delegate;

    public NamedRateLimiter(String name, RateLimiter delegate) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        boolean ok = delegate.allowAt(key, nowMillis);
        if (ok) RateLimitMetrics.get().incAllowed(name); else RateLimitMetrics.get().incDenied(name);
        return ok;
    }
}

