package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;
import org.redisson.api.StreamMessageId;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PendingMessage
 */
class PendingMessageTest {

    @Test
    void testBuilder() {
        StreamMessageId messageId = new StreamMessageId(1234567);
        String consumerName = "consumer1";
        Duration idleTime = Duration.ofSeconds(30);
        long deliveryCount = 3;
        long firstDeliveryTime = 1234567890L;

        PendingMessage pendingMessage = PendingMessage.builder()
                .messageId(messageId)
                .consumerName(consumerName)
                .idleTime(idleTime)
                .deliveryCount(deliveryCount)
                .firstDeliveryTime(firstDeliveryTime)
                .build();

        assertEquals(messageId, pendingMessage.getMessageId());
        assertEquals(consumerName, pendingMessage.getConsumerName());
        assertEquals(idleTime, pendingMessage.getIdleTime());
        assertEquals(deliveryCount, pendingMessage.getDeliveryCount());
        assertEquals(firstDeliveryTime, pendingMessage.getFirstDeliveryTime());
    }

    @Test
    void testBuilderWithPartialFields() {
        PendingMessage pendingMessage = PendingMessage.builder()
                .consumerName("consumer1")
                .deliveryCount(1)
                .build();

        assertEquals("consumer1", pendingMessage.getConsumerName());
        assertEquals(1, pendingMessage.getDeliveryCount());
        assertNull(pendingMessage.getMessageId());
        assertNull(pendingMessage.getIdleTime());
        assertEquals(0, pendingMessage.getFirstDeliveryTime());
    }

    @Test
    void testSettersAndGetters() {
        // Create a PendingMessage using builder
        PendingMessage pendingMessage = PendingMessage.builder().build();
        StreamMessageId messageId = new StreamMessageId(999);

        pendingMessage.setMessageId(messageId);
        pendingMessage.setConsumerName("test-consumer");
        pendingMessage.setIdleTime(Duration.ofMinutes(5));
        pendingMessage.setDeliveryCount(10);
        pendingMessage.setFirstDeliveryTime(1111111111L);

        assertEquals(messageId, pendingMessage.getMessageId());
        assertEquals("test-consumer", pendingMessage.getConsumerName());
        assertEquals(Duration.ofMinutes(5), pendingMessage.getIdleTime());
        assertEquals(10, pendingMessage.getDeliveryCount());
        assertEquals(1111111111L, pendingMessage.getFirstDeliveryTime());
    }

    @Test
    void testWithNullValues() {
        PendingMessage pendingMessage = PendingMessage.builder()
                .messageId(null)
                .consumerName(null)
                .idleTime(null)
                .deliveryCount(0)
                .firstDeliveryTime(0)
                .build();

        assertNull(pendingMessage.getMessageId());
        assertNull(pendingMessage.getConsumerName());
        assertNull(pendingMessage.getIdleTime());
        assertEquals(0, pendingMessage.getDeliveryCount());
        assertEquals(0, pendingMessage.getFirstDeliveryTime());
    }

    @Test
    void testDifferentIdleTimes() {
        PendingMessage p1 = PendingMessage.builder()
                .idleTime(Duration.ofMillis(500))
                .build();

        PendingMessage p2 = PendingMessage.builder()
                .idleTime(Duration.ofSeconds(60))
                .build();

        PendingMessage p3 = PendingMessage.builder()
                .idleTime(Duration.ofHours(2))
                .build();

        assertEquals(Duration.ofMillis(500), p1.getIdleTime());
        assertEquals(Duration.ofSeconds(60), p2.getIdleTime());
        assertEquals(Duration.ofHours(2), p3.getIdleTime());
    }

    @Test
    void testDifferentDeliveryCounts() {
        PendingMessage p1 = PendingMessage.builder().deliveryCount(0).build();
        PendingMessage p2 = PendingMessage.builder().deliveryCount(1).build();
        PendingMessage p3 = PendingMessage.builder().deliveryCount(100).build();

        assertEquals(0, p1.getDeliveryCount());
        assertEquals(1, p2.getDeliveryCount());
        assertEquals(100, p3.getDeliveryCount());
    }

    @Test
    void testDifferentStreamMessageIds() {
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(1234567890);
        StreamMessageId id3 = new StreamMessageId(999, 5);

        PendingMessage p1 = PendingMessage.builder().messageId(id1).build();
        PendingMessage p2 = PendingMessage.builder().messageId(id2).build();
        PendingMessage p3 = PendingMessage.builder().messageId(id3).build();

        assertEquals(id1, p1.getMessageId());
        assertEquals(id2, p2.getMessageId());
        assertEquals(id3, p3.getMessageId());
    }

    @Test
    void testLombokDataAnnotation() {
        StreamMessageId messageId = new StreamMessageId(123);

        PendingMessage p1 = PendingMessage.builder()
                .messageId(messageId)
                .consumerName("consumer1")
                .idleTime(Duration.ofSeconds(10))
                .deliveryCount(2)
                .firstDeliveryTime(1000L)
                .build();

        PendingMessage p2 = PendingMessage.builder()
                .messageId(messageId)
                .consumerName("consumer1")
                .idleTime(Duration.ofSeconds(10))
                .deliveryCount(2)
                .firstDeliveryTime(1000L)
                .build();

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }

    @Test
    void testWithZeroIdleTime() {
        PendingMessage pendingMessage = PendingMessage.builder()
                .idleTime(Duration.ZERO)
                .build();

        assertEquals(Duration.ZERO, pendingMessage.getIdleTime());
    }

    @Test
    void testWithNegativeDeliveryCount() {
        // This might be a valid scenario in some error conditions
        PendingMessage pendingMessage = PendingMessage.builder()
                .deliveryCount(-1)
                .build();

        assertEquals(-1, pendingMessage.getDeliveryCount());
    }

    @Test
    void testWithZeroFirstDeliveryTime() {
        PendingMessage pendingMessage = PendingMessage.builder()
                .firstDeliveryTime(0)
                .build();

        assertEquals(0, pendingMessage.getFirstDeliveryTime());
    }

    @Test
    void testWithDifferentConsumerNames() {
        PendingMessage p1 = PendingMessage.builder().consumerName("consumer-alpha").build();
        PendingMessage p2 = PendingMessage.builder().consumerName("consumer-beta").build();
        PendingMessage p3 = PendingMessage.builder().consumerName("consumer-123").build();

        assertEquals("consumer-alpha", p1.getConsumerName());
        assertEquals("consumer-beta", p2.getConsumerName());
        assertEquals("consumer-123", p3.getConsumerName());
    }

    @Test
    void testMultiplePendingMessages() {
        StreamMessageId id1 = new StreamMessageId(100);
        StreamMessageId id2 = new StreamMessageId(200);
        StreamMessageId id3 = new StreamMessageId(300);

        PendingMessage p1 = PendingMessage.builder()
                .messageId(id1)
                .consumerName("consumer1")
                .idleTime(Duration.ofSeconds(10))
                .deliveryCount(1)
                .firstDeliveryTime(1000L)
                .build();

        PendingMessage p2 = PendingMessage.builder()
                .messageId(id2)
                .consumerName("consumer1")
                .idleTime(Duration.ofSeconds(20))
                .deliveryCount(2)
                .firstDeliveryTime(2000L)
                .build();

        PendingMessage p3 = PendingMessage.builder()
                .messageId(id3)
                .consumerName("consumer2")
                .idleTime(Duration.ofSeconds(5))
                .deliveryCount(1)
                .firstDeliveryTime(3000L)
                .build();

        assertEquals(id1, p1.getMessageId());
        assertEquals(id2, p2.getMessageId());
        assertEquals(id3, p3.getMessageId());
        assertEquals(1, p1.getDeliveryCount());
        assertEquals(2, p2.getDeliveryCount());
        assertEquals(1, p3.getDeliveryCount());
    }

    @Test
    void testEmptyConsumerName() {
        PendingMessage pendingMessage = PendingMessage.builder()
                .consumerName("")
                .build();

        assertEquals("", pendingMessage.getConsumerName());
    }

    @Test
    void testVeryLargeDeliveryCount() {
        PendingMessage pendingMessage = PendingMessage.builder()
                .deliveryCount(Long.MAX_VALUE)
                .build();

        assertEquals(Long.MAX_VALUE, pendingMessage.getDeliveryCount());
    }
}
