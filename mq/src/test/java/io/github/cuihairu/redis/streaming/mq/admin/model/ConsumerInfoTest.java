package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsumerInfo
 */
class ConsumerInfoTest {

    @Test
    void testBuilder() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("consumer-1")
                .pending(5)
                .idleTime(30000)
                .build();

        assertEquals("consumer-1", consumerInfo.getName());
        assertEquals(5, consumerInfo.getPending());
        assertEquals(30000, consumerInfo.getIdleTime());
    }

    @Test
    void testBuilderWithPartialFields() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("partial-consumer")
                .build();

        assertEquals("partial-consumer", consumerInfo.getName());
        assertEquals(0, consumerInfo.getPending());
        assertEquals(0, consumerInfo.getIdleTime());
    }

    @Test
    void testSettersAndGetters() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder().build();

        consumerInfo.setName("new-consumer");
        consumerInfo.setPending(10);
        consumerInfo.setIdleTime(60000);

        assertEquals("new-consumer", consumerInfo.getName());
        assertEquals(10, consumerInfo.getPending());
        assertEquals(60000, consumerInfo.getIdleTime());
    }

    @Test
    void testWithNullName() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name(null)
                .build();

        assertNull(consumerInfo.getName());
        assertEquals(0, consumerInfo.getPending());
        assertEquals(0, consumerInfo.getIdleTime());
    }

    @Test
    void testWithEmptyName() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("")
                .build();

        assertEquals("", consumerInfo.getName());
    }

    @Test
    void testWithZeroPending() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("idle-consumer")
                .pending(0)
                .build();

        assertEquals(0, consumerInfo.getPending());
    }

    @Test
    void testWithZeroIdleTime() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("active-consumer")
                .idleTime(0)
                .build();

        assertEquals(0, consumerInfo.getIdleTime());
    }

    @Test
    void testWithLargePendingCount() {
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("busy-consumer")
                .pending(10000)
                .build();

        assertEquals(10000, consumerInfo.getPending());
    }

    @Test
    void testWithLongIdleTime() {
        // One hour in milliseconds
        ConsumerInfo consumerInfo = ConsumerInfo.builder()
                .name("stale-consumer")
                .idleTime(3600000)
                .build();

        assertEquals(3600000, consumerInfo.getIdleTime());
    }

    @Test
    void testLombokDataAnnotation() {
        ConsumerInfo c1 = ConsumerInfo.builder()
                .name("consumer-1")
                .pending(5)
                .idleTime(1000)
                .build();

        ConsumerInfo c2 = ConsumerInfo.builder()
                .name("consumer-1")
                .pending(5)
                .idleTime(1000)
                .build();

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void testDifferentPendingCounts() {
        ConsumerInfo c1 = ConsumerInfo.builder().pending(0).build();
        ConsumerInfo c2 = ConsumerInfo.builder().pending(1).build();
        ConsumerInfo c3 = ConsumerInfo.builder().pending(100).build();
        ConsumerInfo c4 = ConsumerInfo.builder().pending(1000000).build();

        assertEquals(0, c1.getPending());
        assertEquals(1, c2.getPending());
        assertEquals(100, c3.getPending());
        assertEquals(1000000, c4.getPending());
    }

    @Test
    void testDifferentIdleTimes() {
        ConsumerInfo c1 = ConsumerInfo.builder().idleTime(0).build();
        ConsumerInfo c2 = ConsumerInfo.builder().idleTime(100).build();
        ConsumerInfo c3 = ConsumerInfo.builder().idleTime(5000).build();
        ConsumerInfo c4 = ConsumerInfo.builder().idleTime(60000).build();

        assertEquals(0, c1.getIdleTime());
        assertEquals(100, c2.getIdleTime());
        assertEquals(5000, c3.getIdleTime());
        assertEquals(60000, c4.getIdleTime());
    }

    @Test
    void testDifferentConsumerNames() {
        ConsumerInfo c1 = ConsumerInfo.builder().name("consumer-alpha").build();
        ConsumerInfo c2 = ConsumerInfo.builder().name("consumer-beta").build();
        ConsumerInfo c3 = ConsumerInfo.builder().name("consumer-123").build();
        ConsumerInfo c4 = ConsumerInfo.builder().name("consumer-with-dashes").build();

        assertEquals("consumer-alpha", c1.getName());
        assertEquals("consumer-beta", c2.getName());
        assertEquals("consumer-123", c3.getName());
        assertEquals("consumer-with-dashes", c4.getName());
    }

    @Test
    void testMultipleConsumerInfos() {
        ConsumerInfo c1 = ConsumerInfo.builder()
                .name("consumer-1")
                .pending(10)
                .idleTime(5000)
                .build();

        ConsumerInfo c2 = ConsumerInfo.builder()
                .name("consumer-2")
                .pending(20)
                .idleTime(10000)
                .build();

        ConsumerInfo c3 = ConsumerInfo.builder()
                .name("consumer-3")
                .pending(0)
                .idleTime(0)
                .build();

        assertEquals("consumer-1", c1.getName());
        assertEquals("consumer-2", c2.getName());
        assertEquals("consumer-3", c3.getName());
        assertEquals(10, c1.getPending());
        assertEquals(20, c2.getPending());
        assertEquals(0, c3.getPending());
    }

    @Test
    void testActiveConsumer() {
        // Active consumer has no pending messages and zero idle time
        ConsumerInfo consumer = ConsumerInfo.builder()
                .name("active-consumer")
                .pending(0)
                .idleTime(0)
                .build();

        assertEquals("active-consumer", consumer.getName());
        assertEquals(0, consumer.getPending());
        assertEquals(0, consumer.getIdleTime());
    }

    @Test
    void testBusyConsumer() {
        // Busy consumer has many pending messages
        ConsumerInfo consumer = ConsumerInfo.builder()
                .name("busy-consumer")
                .pending(1000)
                .idleTime(100)
                .build();

        assertEquals(1000, consumer.getPending());
        assertEquals(100, consumer.getIdleTime());
    }

    @Test
    void testStaleConsumer() {
        // Stale consumer has been idle for a long time
        ConsumerInfo consumer = ConsumerInfo.builder()
                .name("stale-consumer")
                .pending(5)
                .idleTime(300000) // 5 minutes
                .build();

        assertEquals(5, consumer.getPending());
        assertEquals(300000, consumer.getIdleTime());
    }

    @Test
    void testNegativeIdleTime() {
        // This might not be a normal scenario but testing for robustness
        ConsumerInfo consumer = ConsumerInfo.builder()
                .name("test-consumer")
                .idleTime(-1000)
                .build();

        assertEquals(-1000, consumer.getIdleTime());
    }

    @Test
    void testToString() {
        ConsumerInfo consumer = ConsumerInfo.builder()
                .name("test-consumer")
                .pending(5)
                .idleTime(1000)
                .build();

        String str = consumer.toString();
        assertTrue(str.contains("test-consumer") || str.contains("5") || str.contains("1000"));
    }
}
