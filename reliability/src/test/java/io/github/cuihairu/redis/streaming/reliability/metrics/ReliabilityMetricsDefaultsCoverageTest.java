package io.github.cuihairu.redis.streaming.reliability.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Covers {@code ReliabilityMetrics} noop collector overrides and interface default methods. */
class ReliabilityMetricsDefaultsCoverageTest {

    @Test
    void noopCollectorAcceptsAllCallbacks() {
        ReliabilityMetricsCollector noop = ReliabilityMetrics.get();
        assertNotNull(noop);
        assertDoesNotThrow(() -> noop.recordDlqReplay("t", 0, true, 10L));
        assertDoesNotThrow(() -> noop.incDlqDelete("t"));
        assertDoesNotThrow(() -> noop.incDlqClear("t", 3));
    }

    @Test
    void interfaceDefaultMethodsAreInvokable() {
        AtomicInteger deletes = new AtomicInteger();
        ReliabilityMetricsCollector collector = new ReliabilityMetricsCollector() {
            @Override
            public void recordDlqReplay(String topic, int partitionId, boolean success, long durationNanos) {
            }
        };
        collector.incDlqDelete("topic-x");
        collector.incDlqClear("topic-x", 5);
        deletes.incrementAndGet();
        assertNotNull(collector);
    }

    @Test
    void setCollectorRestoresNoop() {
        ReliabilityMetrics.setCollector(ReliabilityMetrics.get());
        assertNotNull(ReliabilityMetrics.get());
    }
}
