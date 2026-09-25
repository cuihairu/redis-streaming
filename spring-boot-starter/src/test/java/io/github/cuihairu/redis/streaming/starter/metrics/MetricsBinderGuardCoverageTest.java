package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the negative-input guards of the micrometer collectors and the null/fallback outcomes of
 * the MQ and ClientInvoker gauge functions.
 */
class MetricsBinderGuardCoverageTest {

    @Test
    void runtimeCollectorIgnoresNegativeMeasurements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisRuntimeMicrometerCollector collector = new RedisRuntimeMicrometerCollector(registry);

        collector.recordKeyedStateSize("j", "t", "g", "op", "st", 0, -1);
        collector.recordKeyedStateReadLatency("j", "t", "g", "op", "st", 0, -5);
        collector.recordKeyedStateWriteLatency("j", "t", "g", "op", "st", 0, -5);
        assertEquals(0, registry.getMeters().size(), "negative measurements must not create meters");

        collector.recordKeyedStateSize("j", "t", "g", "op", "st", 0, 3);
        collector.recordKeyedStateReadLatency("j", "t", "g", "op", "st", 0, 4);
        collector.recordKeyedStateWriteLatency("j", "t", "g", "op", "st", 0, 5);
        assertNotNull(registry.find("redis_streaming_runtime_keyed_state_size_fields").summary());
        assertNotNull(registry.find("redis_streaming_runtime_keyed_state_read_latency_ms").timer());
        assertNotNull(registry.find("redis_streaming_runtime_keyed_state_write_latency_ms").timer());
    }

    @Test
    void mqCollectorIgnoresNegativeBackpressureWait() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqMicrometerCollector collector = new MqMicrometerCollector(registry);

        collector.recordBackpressureWait("consumer-1", -1);
        assertEquals(0, registry.getMeters().size());

        collector.recordBackpressureWait("consumer-1", 5);
        assertNotNull(registry.find("redis_streaming_mq_backpressure_wait_total").counter());
        assertNotNull(registry.find("redis_streaming_mq_backpressure_wait_ms").timer());
    }

    @Test
    void retentionCollectorSkipsZeroDlqDeletions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionMicrometerCollector collector = new RetentionMicrometerCollector(registry);

        collector.recordDlqTrim("t", 0, "maxlen");
        assertNotNull(registry.find("redis_streaming_mq_trim_attempts_total").counter());
        assertNull(registry.find("redis_streaming_mq_trim_deleted_total").counter(),
                "zero deletions must not create the deleted counter");

        collector.recordDlqTrim("t", 3, "maxlen");
        assertEquals(3.0, registry.find("redis_streaming_mq_trim_deleted_total").counter().count());
    }

    @Test
    void mqMessagesGaugeSkipsMissingAndUnrealQueues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        DeadLetterService dlq = mock(DeadLetterService.class);
        when(admin.listAllTopics()).thenReturn(java.util.List.of("t1", "t2", "t3"));
        when(admin.getQueueInfo("t1")).thenReturn(null);
        when(admin.getQueueInfo("t2")).thenReturn(QueueInfo.builder().exists(false).length(9).build());
        when(admin.getQueueInfo("t3")).thenReturn(QueueInfo.builder().exists(true).length(5).build());

        new MqMetricsBinder(admin, dlq).bindTo(registry);

        assertEquals(5.0, registry.get("redis_streaming_mq_messages_total").gauge().value());
    }

    @Test
    void clientInvokerGaugesFallBackToZeroWithoutSnapshots() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClientInvoker invoker = mock(ClientInvoker.class);
        ClientInvokerMetricsBinder binder = new ClientInvokerMetricsBinder(invoker);
        binder.bindTo(registry);

        when(invoker.getMetricsSnapshot()).thenReturn(Map.of());
        assertEquals(0.0, registry.get("client.invoker.total.attempts").gauge().value());

        when(invoker.getMetricsSnapshot()).thenReturn(Map.of("other", Map.of("attempts", 42L)));
        assertEquals(0.0, registry.get("client.invoker.total.attempts").gauge().value());

        when(invoker.getMetricsSnapshot()).thenReturn(Map.of("total", Map.of("attempts", 7L)));
        assertEquals(7.0, registry.get("client.invoker.total.attempts").gauge().value());
    }
}
