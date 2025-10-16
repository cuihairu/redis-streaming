package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Micrometer-backed collector for retention trimming metrics. */
public class RetentionMicrometerCollector implements RetentionMetricsCollector {
    private final MeterRegistry registry;
    private final Map<String, Counter> attempts = new ConcurrentHashMap<>();
    private final Map<String, Counter> deleted = new ConcurrentHashMap<>();

    public RetentionMicrometerCollector(MeterRegistry registry) { this.registry = registry; }

    @Override
    public void recordTrim(String topic, int partitionId, long deletedCount, String reason) {
        Counter a = counter(attempts, "redis_streaming_mq_trim_attempts_total", topic, partitionId, reason, "main");
        a.increment();
        if (deletedCount > 0) counter(deleted, "redis_streaming_mq_trim_deleted_total", topic, partitionId, reason, "main").increment(deletedCount);
    }

    @Override
    public void recordDlqTrim(String topic, long deletedCount, String reason) {
        Counter a = counter(attempts, "redis_streaming_mq_trim_attempts_total", topic, -1, reason, "dlq");
        a.increment();
        if (deletedCount > 0) counter(deleted, "redis_streaming_mq_trim_deleted_total", topic, -1, reason, "dlq").increment(deletedCount);
    }

    private Counter counter(Map<String, Counter> cache, String name, String topic, int pid, String reason, String stream) {
        String key = name + "|" + topic + "|" + pid + "|" + reason + "|" + stream;
        return cache.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("topic", topic)
                .tag("partition", Integer.toString(pid))
                .tag("reason", reason)
                .tag("stream", stream)
                .register(registry));
    }
}

