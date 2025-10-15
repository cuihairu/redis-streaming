package io.github.cuihairu.redis.streaming.mq.metrics;

/**
 * Static access to MQ metrics collector. Defaults to Noop; can be overridden by starter.
 */
public final class MqMetrics {
    private static volatile MqMetricsCollector COLLECTOR = new Noop();

    private MqMetrics() {}

    public static void setCollector(MqMetricsCollector c) {
        if (c != null) COLLECTOR = c;
    }

    public static MqMetricsCollector get() {
        return COLLECTOR;
    }

    private static class Noop implements MqMetricsCollector {
        @Override public void incProduced(String topic, int partitionId) {}
        @Override public void incConsumed(String topic, int partitionId) {}
        @Override public void incAcked(String topic, int partitionId) {}
        @Override public void incRetried(String topic, int partitionId) {}
        @Override public void incDeadLetter(String topic, int partitionId) {}
        @Override public void recordHandleLatency(String topic, int partitionId, long millis) {}
    }
}

