package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;
import org.redisson.api.StreamMessageId;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueueInfo
 */
class QueueInfoTest {

    @Test
    void testBuilder() {
        StreamMessageId firstId = new StreamMessageId(1);
        StreamMessageId lastId = new StreamMessageId(100);
        Instant createdAt = Instant.ofEpochSecond(1609459200); // 2021-01-01 00:00:00
        Instant lastUpdatedAt = Instant.ofEpochSecond(1609545600); // 2021-01-02 00:00:00

        QueueInfo queueInfo = QueueInfo.builder()
                .topic("test-topic")
                .length(100)
                .consumerGroupCount(3)
                .firstMessageId(firstId)
                .lastMessageId(lastId)
                .createdAt(createdAt)
                .lastUpdatedAt(lastUpdatedAt)
                .exists(true)
                .build();

        assertEquals("test-topic", queueInfo.getTopic());
        assertEquals(100, queueInfo.getLength());
        assertEquals(3, queueInfo.getConsumerGroupCount());
        assertEquals(firstId, queueInfo.getFirstMessageId());
        assertEquals(lastId, queueInfo.getLastMessageId());
        assertEquals(createdAt, queueInfo.getCreatedAt());
        assertEquals(lastUpdatedAt, queueInfo.getLastUpdatedAt());
        assertTrue(queueInfo.isExists());
    }

    @Test
    void testBuilderWithPartialFields() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("partial-topic")
                .exists(false)
                .build();

        assertEquals("partial-topic", queueInfo.getTopic());
        assertFalse(queueInfo.isExists());
        assertEquals(0, queueInfo.getLength());
        assertEquals(0, queueInfo.getConsumerGroupCount());
        assertNull(queueInfo.getFirstMessageId());
        assertNull(queueInfo.getLastMessageId());
        assertNull(queueInfo.getCreatedAt());
        assertNull(queueInfo.getLastUpdatedAt());
    }

    @Test
    void testSettersAndGetters() {
        QueueInfo queueInfo = QueueInfo.builder().build();
        StreamMessageId firstId = new StreamMessageId(50);
        StreamMessageId lastId = new StreamMessageId(150);
        Instant now = Instant.now();

        queueInfo.setTopic("new-topic");
        queueInfo.setLength(500);
        queueInfo.setConsumerGroupCount(5);
        queueInfo.setFirstMessageId(firstId);
        queueInfo.setLastMessageId(lastId);
        queueInfo.setCreatedAt(now);
        queueInfo.setLastUpdatedAt(now);
        queueInfo.setExists(true);

        assertEquals("new-topic", queueInfo.getTopic());
        assertEquals(500, queueInfo.getLength());
        assertEquals(5, queueInfo.getConsumerGroupCount());
        assertEquals(firstId, queueInfo.getFirstMessageId());
        assertEquals(lastId, queueInfo.getLastMessageId());
        assertEquals(now, queueInfo.getCreatedAt());
        assertEquals(now, queueInfo.getLastUpdatedAt());
        assertTrue(queueInfo.isExists());
    }

    @Test
    void testWithNullValues() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic(null)
                .firstMessageId(null)
                .lastMessageId(null)
                .createdAt(null)
                .lastUpdatedAt(null)
                .build();

        assertNull(queueInfo.getTopic());
        assertNull(queueInfo.getFirstMessageId());
        assertNull(queueInfo.getLastMessageId());
        assertNull(queueInfo.getCreatedAt());
        assertNull(queueInfo.getLastUpdatedAt());
        assertEquals(0, queueInfo.getLength());
        assertEquals(0, queueInfo.getConsumerGroupCount());
    }

    @Test
    void testWithEmptyTopic() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("")
                .build();

        assertEquals("", queueInfo.getTopic());
    }

    @Test
    void testWithZeroLength() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("empty-topic")
                .length(0)
                .build();

        assertEquals(0, queueInfo.getLength());
        assertEquals("empty-topic", queueInfo.getTopic());
    }

    @Test
    void testWithZeroConsumerGroups() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("no-groups-topic")
                .consumerGroupCount(0)
                .build();

        assertEquals(0, queueInfo.getConsumerGroupCount());
    }

    @Test
    void testNonExistentQueue() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("non-existent")
                .exists(false)
                .length(0)
                .consumerGroupCount(0)
                .build();

        assertFalse(queueInfo.isExists());
        assertEquals(0, queueInfo.getLength());
        assertEquals(0, queueInfo.getConsumerGroupCount());
    }

    @Test
    void testExistingQueue() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("existing-topic")
                .exists(true)
                .length(1000)
                .consumerGroupCount(10)
                .build();

        assertTrue(queueInfo.isExists());
        assertEquals(1000, queueInfo.getLength());
        assertEquals(10, queueInfo.getConsumerGroupCount());
    }

    @Test
    void testLombokDataAnnotation() {
        StreamMessageId firstId = new StreamMessageId(1);
        StreamMessageId lastId = new StreamMessageId(100);
        Instant createdAt = Instant.ofEpochSecond(1000000);
        Instant lastUpdatedAt = Instant.ofEpochSecond(2000000);

        QueueInfo q1 = QueueInfo.builder()
                .topic("topic1")
                .length(50)
                .consumerGroupCount(2)
                .firstMessageId(firstId)
                .lastMessageId(lastId)
                .createdAt(createdAt)
                .lastUpdatedAt(lastUpdatedAt)
                .exists(true)
                .build();

        QueueInfo q2 = QueueInfo.builder()
                .topic("topic1")
                .length(50)
                .consumerGroupCount(2)
                .firstMessageId(firstId)
                .lastMessageId(lastId)
                .createdAt(createdAt)
                .lastUpdatedAt(lastUpdatedAt)
                .exists(true)
                .build();

        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }

    @Test
    void testDifferentLengths() {
        QueueInfo q1 = QueueInfo.builder().length(0).build();
        QueueInfo q2 = QueueInfo.builder().length(1).build();
        QueueInfo q3 = QueueInfo.builder().length(100).build();
        QueueInfo q4 = QueueInfo.builder().length(1000000).build();

        assertEquals(0, q1.getLength());
        assertEquals(1, q2.getLength());
        assertEquals(100, q3.getLength());
        assertEquals(1000000, q4.getLength());
    }

    @Test
    void testDifferentConsumerGroupCounts() {
        QueueInfo q1 = QueueInfo.builder().consumerGroupCount(0).build();
        QueueInfo q2 = QueueInfo.builder().consumerGroupCount(1).build();
        QueueInfo q3 = QueueInfo.builder().consumerGroupCount(5).build();
        QueueInfo q4 = QueueInfo.builder().consumerGroupCount(100).build();

        assertEquals(0, q1.getConsumerGroupCount());
        assertEquals(1, q2.getConsumerGroupCount());
        assertEquals(5, q3.getConsumerGroupCount());
        assertEquals(100, q4.getConsumerGroupCount());
    }

    @Test
    void testDifferentTimestamps() {
        Instant past = Instant.ofEpochSecond(1609459200); // 2021-01-01
        Instant now = Instant.now();
        Instant future = Instant.ofEpochSecond(1893456000); // 2030-01-01

        QueueInfo queueInfo = QueueInfo.builder()
                .createdAt(past)
                .lastUpdatedAt(now)
                .build();

        assertEquals(past, queueInfo.getCreatedAt());
        assertEquals(now, queueInfo.getLastUpdatedAt());
    }

    @Test
    void testMultipleQueueInfos() {
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(50);

        QueueInfo q1 = QueueInfo.builder()
                .topic("queue1")
                .length(100)
                .firstMessageId(id1)
                .exists(true)
                .build();

        QueueInfo q2 = QueueInfo.builder()
                .topic("queue2")
                .length(200)
                .lastMessageId(id2)
                .exists(true)
                .build();

        assertEquals("queue1", q1.getTopic());
        assertEquals("queue2", q2.getTopic());
        assertEquals(100, q1.getLength());
        assertEquals(200, q2.getLength());
        assertEquals(id1, q1.getFirstMessageId());
        assertEquals(id2, q2.getLastMessageId());
    }

    @Test
    void testWithSpecialCharactersInTopic() {
        QueueInfo queueInfo = QueueInfo.builder()
                .topic("topic:with:colons")
                .build();

        assertEquals("topic:with:colons", queueInfo.getTopic());
    }

    @Test
    void testSameFirstAndLastMessageId() {
        StreamMessageId singleId = new StreamMessageId(1);

        QueueInfo queueInfo = QueueInfo.builder()
                .firstMessageId(singleId)
                .lastMessageId(singleId)
                .build();

        assertEquals(singleId, queueInfo.getFirstMessageId());
        assertEquals(singleId, queueInfo.getLastMessageId());
    }

    @Test
    void testNullMessageIds() {
        QueueInfo queueInfo = QueueInfo.builder()
                .firstMessageId(null)
                .lastMessageId(null)
                .build();

        assertNull(queueInfo.getFirstMessageId());
        assertNull(queueInfo.getLastMessageId());
    }
}
