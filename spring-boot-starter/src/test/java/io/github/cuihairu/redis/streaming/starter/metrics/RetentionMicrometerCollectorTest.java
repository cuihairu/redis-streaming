package io.github.cuihairu.redis.streaming.starter.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RetentionMicrometerCollectorTest {

    @Test
    public void testRecordTrimAndDlqTrim() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionMicrometerCollector c = new RetentionMicrometerCollector(registry);

        c.recordTrim("t", 0, 0, "periodic");
        c.recordTrim("t", 0, 3, "periodic");
        c.recordDlqTrim("t", 2, "dlq");

        assertEquals(2.0, registry.get("redis_streaming_mq_trim_attempts_total")
                .tag("topic", "t").tag("partition", "0").tag("reason", "periodic").tag("stream", "main")
                .counter().count(), 0.0001);
        assertEquals(3.0, registry.get("redis_streaming_mq_trim_deleted_total")
                .tag("topic", "t").tag("partition", "0").tag("reason", "periodic").tag("stream", "main")
                .counter().count(), 0.0001);

        assertEquals(1.0, registry.get("redis_streaming_mq_trim_attempts_total")
                .tag("topic", "t").tag("partition", "-1").tag("reason", "dlq").tag("stream", "dlq")
                .counter().count(), 0.0001);
        assertEquals(2.0, registry.get("redis_streaming_mq_trim_deleted_total")
                .tag("topic", "t").tag("partition", "-1").tag("reason", "dlq").tag("stream", "dlq")
                .counter().count(), 0.0001);
    }

    @Test
    public void testDeletedCounterNotCreatedWhenDeletedIsZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionMicrometerCollector c = new RetentionMicrometerCollector(registry);

        c.recordTrim("t", 0, 0, "periodic");
        assertNotNull(registry.find("redis_streaming_mq_trim_attempts_total").counter());
        assertNull(registry.find("redis_streaming_mq_trim_deleted_total").counter());
    }
}

