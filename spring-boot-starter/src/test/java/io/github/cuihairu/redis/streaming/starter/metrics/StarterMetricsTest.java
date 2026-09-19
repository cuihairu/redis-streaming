package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StarterMetricsTest {

    // ---------- RedisRuntimeMicrometerCollector ----------

    @Test
    void redisRuntimeCollectorRegistersAllFamilies() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        RedisRuntimeMicrometerCollector c = new RedisRuntimeMicrometerCollector(reg);

        c.incJobStarted("j");
        c.incJobCanceled("j");
        c.incCheckpointTriggered("j");
        c.incCheckpointCompleted("j");
        c.incCheckpointFailed("j");
        c.recordCheckpointDuration("j", 12);
        c.recordCheckpointDrainDuration("j", 3);
        c.recordCheckpointStoreDuration("j", 4);
        c.recordCheckpointSinkCommitDuration("j", 5);
        c.incPipelineStarted("j", "t", "g");
        c.incPipelineStartFailed("j", "t", "g");
        c.incHandleSuccess("j", "t", "g");
        c.incHandleError("j", "t", "g");
        c.recordHandleLatency("j", "t", "g", 7);
        c.recordKeyedStateSize("j", "t", "g", "op", "st", 1, 42);
        c.incKeyedStateHotKey("j", "t", "g", "op", "st", 1, 99);
        c.incKeyedStateRead("j", "t", "g", "op", "st", 1);
        c.incKeyedStateWrite("j", "t", "g", "op", "st", 1);
        c.incKeyedStateDelete("j", "t", "g", "op", "st", 1);
        c.recordKeyedStateReadLatency("j", "t", "g", "op", "st", 1, 2);
        c.recordKeyedStateWriteLatency("j", "t", "g", "op", "st", 1, 3);
        c.setEventTimeTimerQueueSize("j", "t", "g", 6);
        c.setWatermarkMs("j", "t", "g", 1234L);
        c.incWindowLateDropped("j", "t", "g", "op", "w", 1);
        c.incWindowFired("j", "t", "g", "op", "w", 1);

        assertEquals(1.0, reg.get("redis_streaming_runtime_job_started_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_job_canceled_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_checkpoint_triggered_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_checkpoint_completed_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_checkpoint_failed_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_pipeline_started_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_handle_success_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_handle_error_total").counter().count());
        assertEquals(42.0, reg.get("redis_streaming_runtime_keyed_state_size_fields").summary().totalAmount());
        assertEquals(1.0, reg.get("redis_streaming_runtime_keyed_state_hot_key_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_keyed_state_read_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_keyed_state_write_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_keyed_state_delete_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_window_late_dropped_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_runtime_window_fired_total").counter().count());
        assertEquals(1, reg.get("redis_streaming_runtime_handle_latency_ms").timer().count());
        assertEquals(6.0, reg.get("redis_streaming_runtime_event_time_timer_queue_size").gauge().value());
        assertEquals(1234.0, reg.get("redis_streaming_runtime_watermark_ms").gauge().value());

        // second call for the same key reuses cached meters (no duplicate registration)
        c.incJobStarted("j");
        assertEquals(2.0, reg.get("redis_streaming_runtime_job_started_total").counter().count());
    }

    // ---------- MqMicrometerCollector ----------

    @Test
    void mqCollectorRegistersAllFamilies() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(reg);

        c.incProduced("t", 0);
        c.incConsumed("t", 1);
        c.incAcked("t", 1);
        c.incRetried("t", 2);
        c.incDeadLetter("t", 2);
        c.incPayloadMissing("t", 2);
        c.recordHandleLatency("t", 1, 5);
        c.setInFlight("consumer-1", 3, 10);
        c.recordBackpressureWait("consumer-1", 15);
        c.setEligiblePartitions("consumer-1", "t", "g", 4);
        c.setLeasedPartitions("consumer-1", "t", "g", 2);
        c.setMaxLeasedPartitions("consumer-1", 8);

        assertEquals(1.0, reg.get("redis_streaming_mq_produced_total").tag("topic", "t").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_mq_consumed_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_mq_acked_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_mq_retried_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_mq_dead_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_mq_payload_missing_total").counter().count());
        assertEquals(3.0, reg.get("redis_streaming_mq_inflight").gauge().value());
        assertEquals(10.0, reg.get("redis_streaming_mq_max_inflight").gauge().value());
        assertEquals(1, reg.get("redis_streaming_mq_handle_latency_ms").timer().count());
        assertTrue(reg.getMeters().stream().anyMatch(m -> m.getId().getName().contains("backpressure")));
        assertEquals(4.0, reg.getMeters().stream().filter(m -> m.getId().getName().equals("redis_streaming_mq_eligible_partitions"))
                .mapToDouble(m -> ((Gauge) m).value()).findFirst().orElse(-1));
        assertEquals(2.0, reg.getMeters().stream().filter(m -> m.getId().getName().equals("redis_streaming_mq_leased_partitions"))
                .mapToDouble(m -> ((Gauge) m).value()).findFirst().orElse(-1));
        assertEquals(8.0, reg.getMeters().stream().filter(m -> m.getId().getName().equals("redis_streaming_mq_max_leased_partitions"))
                .mapToDouble(m -> ((Gauge) m).value()).findFirst().orElse(-1));
    }

    @Test
    void mqCollectorGaugesUpdateInPlace() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(reg);
        c.setEligiblePartitions("cons", "t", "g", 3);
        c.setEligiblePartitions("cons", "t", "g", 5);
        c.setLeasedPartitions("cons", "t", "g", 1);
        c.setMaxLeasedPartitions("cons", 4);
        c.setInFlight("cons", 1, 2);
        double eligible = reg.getMeters().stream()
                .filter(m -> m.getId().getName().contains("eligible"))
                .mapToDouble(m -> ((Gauge) m).value()).findFirst().orElse(-1);
        assertEquals(5.0, eligible);
        // in-flight gauge keyed by consumer: latest wins
        assertEquals(1.0, reg.getMeters().stream()
                .filter(m -> m.getId().getName().equals("redis_streaming_mq_inflight"))
                .mapToDouble(m -> ((Gauge) m).value()).findFirst().orElse(-1));
    }

    // ---------- Retention / Reliability / RateLimit collectors ----------

    @Test
    void retentionCollectorMapsTrimEvents() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        RetentionMicrometerCollector c = new RetentionMicrometerCollector(reg);
        c.recordTrim("t", 0, 10, "len");
        c.recordDlqTrim("t", 3, "age");
        c.recordTrim("t", 0, 0, "noop");
        assertEquals(3.0, reg.get("redis_streaming_mq_trim_attempts_total").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
        assertEquals(13.0, reg.get("redis_streaming_mq_trim_deleted_total").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
    }

    @Test
    void reliabilityCollectorMapsDlqOps() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ReliabilityMicrometerCollector c = new ReliabilityMicrometerCollector(reg);
        c.recordDlqReplay("t", 1, true, 100L);
        c.recordDlqReplay("t", 1, false, 300L);
        c.incDlqDelete("t");
        c.incDlqClear("t", 2);
        assertEquals(1.0, reg.get("redis_streaming_dlq_replay_success_total").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_dlq_replay_failure_total").counter().count());
        assertTrue(reg.getMeters().stream().anyMatch(m -> m.getId().getName().equals("redis_streaming_dlq_replay_latency_ms")));
        assertEquals(1.0, reg.get("redis_streaming_dlq_deleted_total").counter().count());
        assertEquals(2.0, reg.get("redis_streaming_dlq_cleared_total").counter().count());
    }

    @Test
    void rateLimitCollectorMapsAllowDeny() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        RateLimitMicrometerCollector c = new RateLimitMicrometerCollector(reg);
        c.incAllowed("api");
        c.incAllowed("api");
        c.incDenied("api");
        assertEquals(2.0, reg.get("redis_streaming_rl_allowed_total").tag("name", "api").counter().count());
        assertEquals(1.0, reg.get("redis_streaming_rl_denied_total").tag("name", "api").counter().count());
    }

    // ---------- binders ----------

    @Test
    void mqMetricsBinderRegistersGauges() {
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        DeadLetterService dlq = mock(DeadLetterService.class);
        QueueInfo qi = mock(QueueInfo.class);
        when(qi.isExists()).thenReturn(true);
        when(qi.getLength()).thenReturn(7L);
        when(admin.listAllTopics()).thenReturn(List.of("t1"));
        when(admin.getQueueInfo("t1")).thenReturn(qi);
        when(dlq.size("t1")).thenReturn(2L);

        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        new MqMetricsBinder(admin, dlq).bindTo(reg);

        assertEquals(1.0, reg.get("redis_streaming_mq_topics_total").gauge().value());
        assertEquals(7.0, reg.get("redis_streaming_mq_messages_total").gauge().value());
        assertEquals(2.0, reg.get("redis_streaming_mq_dlq_total").gauge().value());
    }

    @Test
    void clientInvokerBinderReadsSnapshotSafely() {
        ClientInvoker invoker = mock(ClientInvoker.class);
        when(invoker.getMetricsSnapshot()).thenReturn(Map.of(
                "total", Map.of("attempts", 9L, "successes", 8L, "failures", 1L, "retries", 2L, "cbOpenSkips", 0L)));
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        new ClientInvokerMetricsBinder(invoker).bindTo(reg);
        assertEquals(9.0, reg.get("client.invoker.total.attempts").gauge().value());
        assertEquals(8.0, reg.get("client.invoker.total.successes").gauge().value());

        ClientInvoker failing = mock(ClientInvoker.class);
        when(failing.getMetricsSnapshot()).thenThrow(new RuntimeException("no snapshot"));
        SimpleMeterRegistry reg2 = new SimpleMeterRegistry();
        new ClientInvokerMetricsBinder(failing).bindTo(reg2);
        assertEquals(0.0, reg2.get("client.invoker.total.attempts").gauge().value());
    }
}
