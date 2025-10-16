package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.reliability.metrics.RateLimitMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Micrometer-backed collector for RateLimit metrics. */
public class RateLimitMicrometerCollector implements RateLimitMetricsCollector {
    private final MeterRegistry registry;
    private final Map<String, Counter> allowed = new ConcurrentHashMap<>();
    private final Map<String, Counter> denied = new ConcurrentHashMap<>();

    public RateLimitMicrometerCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void incAllowed(String name) {
        counter(allowed, "redis_streaming_rl_allowed_total", name).increment();
    }

    @Override
    public void incDenied(String name) {
        counter(denied, "redis_streaming_rl_denied_total", name).increment();
    }

    private Counter counter(Map<String, Counter> cache, String metric, String name) {
        String key = metric + "|" + name;
        return cache.computeIfAbsent(key, k -> Counter.builder(metric)
                .tag("name", name)
                .register(registry));
    }
}

