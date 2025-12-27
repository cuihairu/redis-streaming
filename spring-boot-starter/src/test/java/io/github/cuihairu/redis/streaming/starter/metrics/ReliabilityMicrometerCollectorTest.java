package io.github.cuihairu.redis.streaming.starter.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReliabilityMicrometerCollectorTest {

    @Test
    public void testReplayAndDlqCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReliabilityMicrometerCollector c = new ReliabilityMicrometerCollector(registry);

        c.recordDlqReplay("t", 1, true, 1_000_000);
        c.recordDlqReplay("t", 1, false, 2_000_000);
        c.incDlqDelete("t");
        c.incDlqClear("t", 0);
        c.incDlqClear("t", 2);

        assertEquals(1.0, registry.get("redis_streaming_dlq_replay_success_total")
                .tag("topic", "t").tag("partition", "1").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_dlq_replay_failure_total")
                .tag("topic", "t").tag("partition", "1").counter().count(), 0.0001);
        assertTrue(registry.get("redis_streaming_dlq_replay_latency_ms")
                .tag("topic", "t").tag("partition", "1").timer().count() >= 2);

        assertEquals(1.0, registry.get("redis_streaming_dlq_deleted_total")
                .tag("topic", "t").tag("partition", "-1").counter().count(), 0.0001);
        assertEquals(2.0, registry.get("redis_streaming_dlq_cleared_total")
                .tag("topic", "t").tag("partition", "-1").counter().count(), 0.0001);
    }
}

