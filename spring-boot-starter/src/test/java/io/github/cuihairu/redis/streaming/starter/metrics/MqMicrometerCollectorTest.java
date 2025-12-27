package io.github.cuihairu.redis.streaming.starter.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MqMicrometerCollectorTest {

    @Test
    public void testCountersAndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(registry);

        c.incProduced("t", 0);
        c.incProduced("t", 0);
        c.incConsumed("t", 0);
        c.incAcked("t", 0);
        c.incRetried("t", 0);
        c.incDeadLetter("t", 0);
        c.incPayloadMissing("t", 0);
        c.recordHandleLatency("t", 0, 50);

        assertEquals(2.0, registry.get("redis_streaming_mq_produced_total")
                .tag("topic", "t").tag("partition", "0").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_consumed_total")
                .tag("topic", "t").tag("partition", "0").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_acked_total")
                .tag("topic", "t").tag("partition", "0").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_retried_total")
                .tag("topic", "t").tag("partition", "0").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_dead_total")
                .tag("topic", "t").tag("partition", "0").counter().count(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_payload_missing_total")
                .tag("topic", "t").tag("partition", "0").counter().count(), 0.0001);
        assertTrue(registry.get("redis_streaming_mq_handle_latency_ms")
                .tag("topic", "t").tag("partition", "0").timer().count() >= 1);
    }
}

