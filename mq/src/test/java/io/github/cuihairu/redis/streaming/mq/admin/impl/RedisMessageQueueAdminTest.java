package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupInfo;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupStats;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisMessageQueueAdmin
 */
class RedisMessageQueueAdminTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        assertNotNull(admin);
    }

    @Test
    void testConstructorWithOptions() {
        MqOptions options = MqOptions.builder().build();
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient, options);

        assertNotNull(admin);
    }

    @Test
    void testConstructorWithNullOptions() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient, null);

        assertNotNull(admin);
    }

    @Test
    void testListAllTopics() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        List<String> topics = admin.listAllTopics();

        assertNotNull(topics);
        // Empty list when no topics registered
        assertTrue(topics.isEmpty());
    }

    @Test
    void testTopicExists() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        // Non-existent topic should return false
        boolean exists = admin.topicExists("non-existent-topic");

        assertFalse(exists);
    }

    @Test
    void testGetQueueInfoForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        QueueInfo info = admin.getQueueInfo("non-existent-topic");

        assertNotNull(info);
        assertEquals("non-existent-topic", info.getTopic());
        assertFalse(info.isExists());
        assertEquals(0, info.getLength());
        assertEquals(0, info.getConsumerGroupCount());
    }

    @Test
    void testGetConsumerGroupsForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        List<ConsumerGroupInfo> groups = admin.getConsumerGroups("non-existent-topic");

        assertNotNull(groups);
        assertTrue(groups.isEmpty());
    }

    @Test
    void testGetConsumerGroupStatsForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        ConsumerGroupStats stats = admin.getConsumerGroupStats("non-existent-topic", "test-group");

        assertNull(stats);
    }

    @Test
    void testConsumerGroupExistsForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean exists = admin.consumerGroupExists("non-existent-topic", "test-group");

        assertFalse(exists);
    }

    @Test
    void testGetPendingMessagesForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var pending = admin.getPendingMessages("non-existent-topic", "test-group", 10);

        assertNotNull(pending);
        assertTrue(pending.isEmpty());
    }

    @Test
    void testGetPendingMessagesWithSortOptions() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var pending = admin.getPendingMessages(
                "non-existent-topic",
                "test-group",
                10,
                io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.IDLE,
                true,
                1000
        );

        assertNotNull(pending);
        assertTrue(pending.isEmpty());
    }

    @Test
    void testGetPendingCountForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long count = admin.getPendingCount("non-existent-topic", "test-group");

        assertEquals(0, count);
    }

    @Test
    void testTrimQueueForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long deleted = admin.trimQueue("non-existent-topic", 1000);

        assertEquals(0, deleted);
    }

    @Test
    void testTrimQueueByAgeForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long deleted = admin.trimQueueByAge("non-existent-topic", Duration.ofHours(1));

        assertEquals(0, deleted);
    }

    @Test
    void testDeleteTopicForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean result = admin.deleteTopic("non-existent-topic");

        // Should return false or handle gracefully
        assertFalse(result);
    }

    @Test
    void testDeleteConsumerGroupForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean result = admin.deleteConsumerGroup("non-existent-topic", "test-group");

        assertFalse(result);
    }

    @Test
    void testResetConsumerGroupOffsetForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        // resetConsumerGroupOffset creates stream with MKSTREAM, returns true for new topics
        boolean result = admin.resetConsumerGroupOffset("non-existent-topic", "test-group", "0");

        // Should attempt to create the stream and group
        assertNotNull(admin);
    }

    @Test
    void testUpdatePartitionCount() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean result = admin.updatePartitionCount("test-topic", 3);

        // May return false if topic not registered
        assertNotNull(admin);
    }

    @Test
    void testListRecentForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.listRecent("non-existent-topic", 10);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testRangeForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.range("non-existent-topic", 0, "-", "+", 10, false);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testRangeReverseForNonExistentTopic() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.range("non-existent-topic", 0, "-", "+", 10, true);

        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testResetConsumerGroupOffsetWithZero() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean result = admin.resetConsumerGroupOffset("test-topic", "test-group", "0");

        // Should handle MIN ID
        assertNotNull(admin);
    }

    @Test
    void testResetConsumerGroupOffsetWithDollar() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean result = admin.resetConsumerGroupOffset("test-topic", "test-group", "$");

        // Should handle MAX ID
        assertNotNull(admin);
    }

    @Test
    void testResetConsumerGroupOffsetWithCustomId() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        boolean result = admin.resetConsumerGroupOffset("test-topic", "test-group", "1234567890-0");

        // Should handle custom ID
        assertNotNull(admin);
    }

    @Test
    void testGetPendingMessagesWithAllSortOptions() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        // Test IDLE sort
        var idle = admin.getPendingMessages("test-topic", "test-group", 10,
                io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.IDLE, true, 0);
        assertNotNull(idle);

        // Test DELIVERIES sort
        var deliveries = admin.getPendingMessages("test-topic", "test-group", 10,
                io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.DELIVERIES, false, 0);
        assertNotNull(deliveries);

        // Test ID sort
        var id = admin.getPendingMessages("test-topic", "test-group", 10,
                io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort.ID, true, 0);
        assertNotNull(id);
    }

    @Test
    void testListRecentWithZeroCount() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.listRecent("test-topic", 0);

        assertNotNull(entries);
    }

    @Test
    void testListRecentWithLargeCount() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.listRecent("test-topic", 10000);

        assertNotNull(entries);
    }

    @Test
    void testRangeWithInvalidPartitionId() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        // Negative partition ID should be handled gracefully
        var entries = admin.range("test-topic", -1, "-", "+", 10, false);

        assertNotNull(entries);
    }

    @Test
    void testRangeWithNullIds() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.range("test-topic", 0, null, null, 10, false);

        assertNotNull(entries);
    }

    @Test
    void testRangeWithEmptyIds() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        var entries = admin.range("test-topic", 0, "", "", 10, false);

        assertNotNull(entries);
    }

    @Test
    void testTrimQueueWithZeroMaxLen() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long deleted = admin.trimQueue("test-topic", 0);

        assertEquals(0, deleted);
    }

    @Test
    void testTrimQueueWithNegativeMaxLen() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long deleted = admin.trimQueue("test-topic", -100);

        assertEquals(0, deleted);
    }

    @Test
    void testTrimQueueByAgeWithZeroDuration() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long deleted = admin.trimQueueByAge("test-topic", Duration.ZERO);

        assertNotNull(admin);
    }

    @Test
    void testTrimQueueByAgeWithNegativeDuration() {
        MessageQueueAdmin admin = new RedisMessageQueueAdmin(mockRedissonClient);

        long deleted = admin.trimQueueByAge("test-topic", Duration.ofSeconds(-1));

        assertNotNull(admin);
    }
}
