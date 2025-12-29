package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsumerGroupStats
 */
class ConsumerGroupStatsTest {

    @Test
    void testBuilder() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("group1")
                .topic("topic1")
                .pendingCount(100)
                .consumerCount(5)
                .lag(50)
                .hasActiveConsumers(true)
                .slowestConsumer("consumer-3")
                .maxIdleTime(30000)
                .build();

        assertEquals("group1", stats.getGroupName());
        assertEquals("topic1", stats.getTopic());
        assertEquals(100, stats.getPendingCount());
        assertEquals(5, stats.getConsumerCount());
        assertEquals(50, stats.getLag());
        assertTrue(stats.isHasActiveConsumers());
        assertEquals("consumer-3", stats.getSlowestConsumer());
        assertEquals(30000, stats.getMaxIdleTime());
    }

    @Test
    void testBuilderWithPartialFields() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("partial-group")
                .topic("partial-topic")
                .build();

        assertEquals("partial-group", stats.getGroupName());
        assertEquals("partial-topic", stats.getTopic());
        assertEquals(0, stats.getPendingCount());
        assertEquals(0, stats.getConsumerCount());
        assertEquals(0, stats.getLag());
        assertFalse(stats.isHasActiveConsumers());
        assertNull(stats.getSlowestConsumer());
        assertEquals(0, stats.getMaxIdleTime());
    }

    @Test
    void testSettersAndGetters() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder().build();

        stats.setGroupName("new-group");
        stats.setTopic("new-topic");
        stats.setPendingCount(200);
        stats.setConsumerCount(10);
        stats.setLag(75);
        stats.setHasActiveConsumers(true);
        stats.setSlowestConsumer("consumer-1");
        stats.setMaxIdleTime(60000);

        assertEquals("new-group", stats.getGroupName());
        assertEquals("new-topic", stats.getTopic());
        assertEquals(200, stats.getPendingCount());
        assertEquals(10, stats.getConsumerCount());
        assertEquals(75, stats.getLag());
        assertTrue(stats.isHasActiveConsumers());
        assertEquals("consumer-1", stats.getSlowestConsumer());
        assertEquals(60000, stats.getMaxIdleTime());
    }

    @Test
    void testWithNullValues() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName(null)
                .topic(null)
                .slowestConsumer(null)
                .build();

        assertNull(stats.getGroupName());
        assertNull(stats.getTopic());
        assertNull(stats.getSlowestConsumer());
        assertEquals(0, stats.getPendingCount());
        assertEquals(0, stats.getConsumerCount());
        assertEquals(0, stats.getLag());
        assertFalse(stats.isHasActiveConsumers());
        assertEquals(0, stats.getMaxIdleTime());
    }

    @Test
    void testWithEmptyGroupName() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("")
                .build();

        assertEquals("", stats.getGroupName());
    }

    @Test
    void testWithEmptyTopic() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .topic("")
                .build();

        assertEquals("", stats.getTopic());
    }

    @Test
    void testWithZeroPendingCount() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("up-to-date-group")
                .pendingCount(0)
                .build();

        assertEquals(0, stats.getPendingCount());
    }

    @Test
    void testWithZeroConsumerCount() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("no-consumers-group")
                .consumerCount(0)
                .hasActiveConsumers(false)
                .build();

        assertEquals(0, stats.getConsumerCount());
        assertFalse(stats.isHasActiveConsumers());
    }

    @Test
    void testWithZeroLag() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("caught-up-group")
                .lag(0)
                .build();

        assertEquals(0, stats.getLag());
    }

    @Test
    void testWithZeroMaxIdleTime() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("active-group")
                .maxIdleTime(0)
                .build();

        assertEquals(0, stats.getMaxIdleTime());
    }

    @Test
    void testWithNoActiveConsumers() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("inactive-group")
                .hasActiveConsumers(false)
                .build();

        assertFalse(stats.isHasActiveConsumers());
    }

    @Test
    void testWithActiveConsumers() {
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("active-group")
                .hasActiveConsumers(true)
                .build();

        assertTrue(stats.isHasActiveConsumers());
    }

    @Test
    void testLombokDataAnnotation() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder()
                .groupName("group1")
                .topic("topic1")
                .pendingCount(10)
                .consumerCount(2)
                .lag(5)
                .hasActiveConsumers(true)
                .slowestConsumer("c1")
                .maxIdleTime(1000)
                .build();

        ConsumerGroupStats s2 = ConsumerGroupStats.builder()
                .groupName("group1")
                .topic("topic1")
                .pendingCount(10)
                .consumerCount(2)
                .lag(5)
                .hasActiveConsumers(true)
                .slowestConsumer("c1")
                .maxIdleTime(1000)
                .build();

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    void testDifferentPendingCounts() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder().pendingCount(0).build();
        ConsumerGroupStats s2 = ConsumerGroupStats.builder().pendingCount(1).build();
        ConsumerGroupStats s3 = ConsumerGroupStats.builder().pendingCount(1000).build();
        ConsumerGroupStats s4 = ConsumerGroupStats.builder().pendingCount(1000000).build();

        assertEquals(0, s1.getPendingCount());
        assertEquals(1, s2.getPendingCount());
        assertEquals(1000, s3.getPendingCount());
        assertEquals(1000000, s4.getPendingCount());
    }

    @Test
    void testDifferentConsumerCounts() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder().consumerCount(0).build();
        ConsumerGroupStats s2 = ConsumerGroupStats.builder().consumerCount(1).build();
        ConsumerGroupStats s3 = ConsumerGroupStats.builder().consumerCount(10).build();
        ConsumerGroupStats s4 = ConsumerGroupStats.builder().consumerCount(100).build();

        assertEquals(0, s1.getConsumerCount());
        assertEquals(1, s2.getConsumerCount());
        assertEquals(10, s3.getConsumerCount());
        assertEquals(100, s4.getConsumerCount());
    }

    @Test
    void testDifferentLagValues() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder().lag(0).build();
        ConsumerGroupStats s2 = ConsumerGroupStats.builder().lag(1).build();
        ConsumerGroupStats s3 = ConsumerGroupStats.builder().lag(1000).build();
        ConsumerGroupStats s4 = ConsumerGroupStats.builder().lag(10000000).build();

        assertEquals(0, s1.getLag());
        assertEquals(1, s2.getLag());
        assertEquals(1000, s3.getLag());
        assertEquals(10000000, s4.getLag());
    }

    @Test
    void testDifferentMaxIdleTimes() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder().maxIdleTime(0).build();
        ConsumerGroupStats s2 = ConsumerGroupStats.builder().maxIdleTime(100).build();
        ConsumerGroupStats s3 = ConsumerGroupStats.builder().maxIdleTime(60000).build(); // 1 minute
        ConsumerGroupStats s4 = ConsumerGroupStats.builder().maxIdleTime(3600000).build(); // 1 hour

        assertEquals(0, s1.getMaxIdleTime());
        assertEquals(100, s2.getMaxIdleTime());
        assertEquals(60000, s3.getMaxIdleTime());
        assertEquals(3600000, s4.getMaxIdleTime());
    }

    @Test
    void testDifferentSlowestConsumers() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder().slowestConsumer("consumer-1").build();
        ConsumerGroupStats s2 = ConsumerGroupStats.builder().slowestConsumer("consumer-2").build();
        ConsumerGroupStats s3 = ConsumerGroupStats.builder().slowestConsumer("slow-consumer").build();

        assertEquals("consumer-1", s1.getSlowestConsumer());
        assertEquals("consumer-2", s2.getSlowestConsumer());
        assertEquals("slow-consumer", s3.getSlowestConsumer());
    }

    @Test
    void testHealthyConsumerGroup() {
        // Healthy group: low lag, active consumers, low idle time
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("healthy-group")
                .topic("healthy-topic")
                .pendingCount(10)
                .consumerCount(3)
                .lag(5)
                .hasActiveConsumers(true)
                .maxIdleTime(1000)
                .build();

        assertTrue(stats.isHasActiveConsumers());
        assertEquals(5, stats.getLag());
        assertEquals(1000, stats.getMaxIdleTime());
    }

    @Test
    void testLaggingConsumerGroup() {
        // Lagging group: high lag
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("lagging-group")
                .topic("lagging-topic")
                .pendingCount(10000)
                .consumerCount(3)
                .lag(9500)
                .hasActiveConsumers(true)
                .slowestConsumer("consumer-2")
                .maxIdleTime(5000)
                .build();

        assertEquals(9500, stats.getLag());
        assertEquals("consumer-2", stats.getSlowestConsumer());
    }

    @Test
    void testStalledConsumerGroup() {
        // Stalled group: no active consumers
        ConsumerGroupStats stats = ConsumerGroupStats.builder()
                .groupName("stalled-group")
                .topic("stalled-topic")
                .pendingCount(1000)
                .consumerCount(0)
                .lag(1000)
                .hasActiveConsumers(false)
                .maxIdleTime(0)
                .build();

        assertFalse(stats.isHasActiveConsumers());
        assertEquals(0, stats.getConsumerCount());
    }

    @Test
    void testMultipleConsumerGroupStats() {
        ConsumerGroupStats s1 = ConsumerGroupStats.builder()
                .groupName("group1")
                .topic("topic1")
                .pendingCount(100)
                .lag(50)
                .build();

        ConsumerGroupStats s2 = ConsumerGroupStats.builder()
                .groupName("group2")
                .topic("topic2")
                .pendingCount(200)
                .lag(150)
                .build();

        assertEquals("group1", s1.getGroupName());
        assertEquals("group2", s2.getGroupName());
        assertEquals(100, s1.getPendingCount());
        assertEquals(200, s2.getPendingCount());
    }
}
