package io.github.cuihairu.redis.streaming.reliability.metrics;

/** Collector interface for rate limit metrics. */
public interface RateLimitMetricsCollector {
    void incAllowed(String name);
    void incDenied(String name);
}

