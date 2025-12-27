package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class MqMetricsBinderTest {

    @Test
    public void testGaugesReflectAdminAndDlq() {
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        DeadLetterService dlq = mock(DeadLetterService.class);

        when(admin.listAllTopics()).thenReturn(List.of("t1", "t2"));
        when(admin.getQueueInfo("t1")).thenReturn(QueueInfo.builder().topic("t1").exists(true).length(10).build());
        when(admin.getQueueInfo("t2")).thenReturn(QueueInfo.builder().topic("t2").exists(false).length(999).build());
        when(dlq.size("t1")).thenReturn(2L);
        when(dlq.size("t2")).thenReturn(3L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MqMetricsBinder(admin, dlq).bindTo(registry);

        assertEquals(2.0, registry.get("redis_streaming_mq_topics_total").gauge().value(), 0.0001);
        assertEquals(10.0, registry.get("redis_streaming_mq_messages_total").gauge().value(), 0.0001);
        assertEquals(5.0, registry.get("redis_streaming_mq_dlq_total").gauge().value(), 0.0001);
    }
}

