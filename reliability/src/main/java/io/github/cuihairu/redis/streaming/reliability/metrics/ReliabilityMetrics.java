package io.github.cuihairu.redis.streaming.reliability.metrics;

/**
 * Static access to reliability metrics collector. Defaults to Noop; can be overridden by starter.
 */
public final class ReliabilityMetrics {
    private static volatile ReliabilityMetricsCollector COLLECTOR = new Noop();

    private ReliabilityMetrics() {}

    public static void setCollector(ReliabilityMetricsCollector c) {
        if (c != null) COLLECTOR = c;
    }

    public static ReliabilityMetricsCollector get() {
        return COLLECTOR;
    }

    private static class Noop implements ReliabilityMetricsCollector {
        @Override public void recordDlqReplay(String topic, int partitionId, boolean success, long durationNanos) {}
        @Override public void incDlqDelete(String topic) {}
        @Override public void incDlqClear(String topic, long count) {}
    }
}

