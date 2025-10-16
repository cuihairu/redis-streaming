package io.github.cuihairu.redis.streaming.reliability.metrics;

/** Static access to rate limit metrics collector (defaults to Noop). */
public final class RateLimitMetrics {
    private static volatile RateLimitMetricsCollector COLLECTOR = new Noop();

    private RateLimitMetrics() {}

    public static void setCollector(RateLimitMetricsCollector c) {
        if (c != null) COLLECTOR = c;
    }

    public static RateLimitMetricsCollector get() {
        return COLLECTOR;
    }

    private static class Noop implements RateLimitMetricsCollector {
        @Override public void incAllowed(String name) {}
        @Override public void incDenied(String name) {}
    }
}

