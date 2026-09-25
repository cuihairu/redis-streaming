package io.github.cuihairu.redis.streaming.reliability.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the {@code setCollector(null)} guard of the static metric singletons:
 * passing null must leave the current collector untouched.
 */
class StaticMetricsCollectorNullCoverageTest {

    @Test
    void reliabilityMetricsIgnoresNullCollector() {
        ReliabilityMetricsCollector before = ReliabilityMetrics.get();
        try {
            ReliabilityMetrics.setCollector(null);
            assertSame(before, ReliabilityMetrics.get(), "null must not replace the collector");
        } finally {
            ReliabilityMetrics.setCollector(before);
        }
    }

    @Test
    void rateLimitMetricsIgnoresNullCollector() {
        RateLimitMetricsCollector before = RateLimitMetrics.get();
        try {
            RateLimitMetrics.setCollector(null);
            assertSame(before, RateLimitMetrics.get(), "null must not replace the collector");
        } finally {
            RateLimitMetrics.setCollector(before);
        }
    }
}
