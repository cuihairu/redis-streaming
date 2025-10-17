package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer-backed detailed MQ metrics (per topic/partition).
 */
public class MqMicrometerCollector implements MqMetricsCollector {

    private final MeterRegistry registry;
    private final Map<String, Counter> produced = new ConcurrentHashMap<>();
    private final Map<String, Counter> consumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> acked = new ConcurrentHashMap<>();
    private final Map<String, Counter> retried = new ConcurrentHashMap<>();
    private final Map<String, Counter> dead = new ConcurrentHashMap<>();
    private final Map<String, Counter> payloadMissing = new ConcurrentHashMap<>();
    private final Map<String, Timer> handleLatency = new ConcurrentHashMap<>();

    public MqMicrometerCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void incProduced(String topic, int partitionId) {
        counter(produced, "redis_streaming_mq_produced_total", topic, partitionId).increment();
    }

    @Override
    public void incConsumed(String topic, int partitionId) {
        counter(consumed, "redis_streaming_mq_consumed_total", topic, partitionId).increment();
    }

    @Override
    public void incAcked(String topic, int partitionId) {
        counter(acked, "redis_streaming_mq_acked_total", topic, partitionId).increment();
    }

    @Override
    public void incRetried(String topic, int partitionId) {
        counter(retried, "redis_streaming_mq_retried_total", topic, partitionId).increment();
    }

    @Override
    public void incDeadLetter(String topic, int partitionId) {
        counter(dead, "redis_streaming_mq_dead_total", topic, partitionId).increment();
    }

    @Override
    public void recordHandleLatency(String topic, int partitionId, long millis) {
        timer(handleLatency, "redis_streaming_mq_handle_latency_ms", topic, partitionId)
                .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void incPayloadMissing(String topic, int partitionId) {
        counter(payloadMissing, "redis_streaming_mq_payload_missing_total", topic, partitionId).increment();
    }

    private Counter counter(Map<String, Counter> cache, String name, String topic, int pid) {
        String key = name + "|" + topic + "|" + pid;
        return cache.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("topic", topic)
                .tag("partition", Integer.toString(pid))
                .register(registry));
    }

    private Timer timer(Map<String, Timer> cache, String name, String topic, int pid) {
        String key = name + "|" + topic + "|" + pid;
        return cache.computeIfAbsent(key, k -> Timer.builder(name)
                .tag("topic", topic)
                .tag("partition", Integer.toString(pid))
                .register(registry));
    }
}
