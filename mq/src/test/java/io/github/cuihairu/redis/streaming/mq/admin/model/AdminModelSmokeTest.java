package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminModelSmokeTest {

    @Test
    void buildersAndAccessorsWork() {
        QueueInfo qi = QueueInfo.builder()
                .topic("t")
                .length(10)
                .consumerGroupCount(2)
                .firstMessageId(new StreamMessageId(1, 0))
                .lastMessageId(new StreamMessageId(2, 0))
                .createdAt(Instant.EPOCH)
                .lastUpdatedAt(Instant.EPOCH.plusSeconds(1))
                .exists(true)
                .build();
        assertEquals("t", qi.getTopic());
        assertEquals(10, qi.getLength());
        assertNotNull(qi.toString());

        ConsumerInfo ci = ConsumerInfo.builder().name("c1").pending(3).idleTime(7).build();
        assertEquals("c1", ci.getName());

        ConsumerGroupInfo cgi = ConsumerGroupInfo.builder()
                .name("g")
                .consumers(1)
                .pending(2)
                .lastDeliveredId(new StreamMessageId(3, 0))
                .consumerList(java.util.List.of(ci))
                .totalConsumed(9)
                .build();
        assertEquals("g", cgi.getName());
        assertEquals(1, cgi.getConsumers());

        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("g")
                .topic("t")
                .pendingCount(2)
                .consumerCount(1)
                .lag(100)
                .hasActiveConsumers(true)
                .slowestConsumer("c1")
                .maxIdleTime(5)
                .build();
        assertEquals("t", stats.getTopic());
        assertEquals(true, stats.isHasActiveConsumers());

        PendingMessage pm = PendingMessage.builder()
                .messageId(new StreamMessageId(4, 0))
                .consumerName("c1")
                .idleTime(Duration.ofSeconds(1))
                .deliveryCount(2)
                .firstDeliveryTime(123)
                .build();
        assertEquals("c1", pm.getConsumerName());

        MessageEntry me = MessageEntry.builder()
                .id("1-0")
                .partitionId(0)
                .fields(Map.of("k", "v"))
                .build();
        assertEquals("1-0", me.getId());
        assertEquals(0, me.getPartitionId());

        assertNotNull(PendingSort.valueOf("ID"));
        assertNotNull(PendingSort.values());
    }
}

