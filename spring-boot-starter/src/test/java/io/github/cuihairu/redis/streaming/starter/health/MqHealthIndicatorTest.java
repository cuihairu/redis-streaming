package io.github.cuihairu.redis.streaming.starter.health;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqHealthIndicatorTest {

    @Test
    void reportsUpWithTopicCount() {
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("t1", "t2"));

        MqHealthIndicator indicator = new MqHealthIndicator(admin);
        Health health = indicator.health();

        assertEquals("UP", health.getStatus().getCode());
        assertEquals(2, health.getDetails().get("topics"));
    }

    @Test
    void reportsDownOnException() {
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenThrow(new RuntimeException("boom"));

        MqHealthIndicator indicator = new MqHealthIndicator(admin);
        Health health = indicator.health();

        assertEquals("DOWN", health.getStatus().getCode());
        assertNotNull(health.getDetails().get("error"));
    }
}

