package io.github.cuihairu.redis.streaming.mq.metrics;

/** Static access for retention metrics collector. */
public final class RetentionMetrics {
    private static volatile RetentionMetricsCollector COLLECTOR = new Noop();
    private RetentionMetrics() {}
    public static void setCollector(RetentionMetricsCollector c) { if (c != null) COLLECTOR = c; }
    public static RetentionMetricsCollector get() { return COLLECTOR; }
    private static class Noop implements RetentionMetricsCollector {
        @Override public void recordTrim(String topic, int partitionId, long deleted, String reason) {}
        @Override public void recordDlqTrim(String topic, long deleted, String reason) {}
    }
}

