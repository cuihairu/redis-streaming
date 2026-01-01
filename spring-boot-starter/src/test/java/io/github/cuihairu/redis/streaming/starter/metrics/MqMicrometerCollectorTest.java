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
        c.setInFlight("c1", 3, 10);
        c.recordBackpressureWait("c1", 7);
        c.setEligiblePartitions("c1", "t", "g", 2);
        c.setLeasedPartitions("c1", "t", "g", 1);
        c.setMaxLeasedPartitions("c1", 8);

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

        assertEquals(3.0, registry.get("redis_streaming_mq_inflight")
                .tag("consumer", "c1").gauge().value(), 0.0001);
        assertEquals(10.0, registry.get("redis_streaming_mq_max_inflight")
                .tag("consumer", "c1").gauge().value(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_backpressure_wait_total")
                .tag("consumer", "c1").counter().count(), 0.0001);
        assertTrue(registry.get("redis_streaming_mq_backpressure_wait_ms")
                .tag("consumer", "c1").timer().count() >= 1);
        assertEquals(2.0, registry.get("redis_streaming_mq_eligible_partitions")
                .tag("consumer", "c1").tag("topic", "t").tag("group", "g").gauge().value(), 0.0001);
        assertEquals(1.0, registry.get("redis_streaming_mq_leased_partitions")
                .tag("consumer", "c1").tag("topic", "t").tag("group", "g").gauge().value(), 0.0001);
        assertEquals(8.0, registry.get("redis_streaming_mq_max_leased_partitions")
                .tag("consumer", "c1").gauge().value(), 0.0001);
    }
}
