package io.github.cuihairu.redis.streaming.mq.partition;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StreamKeys
 */
class StreamKeysTest {

    // Store original prefixes to restore after tests
    private String originalControlPrefix;
    private String originalStreamPrefix;

    @AfterEach
    void restoreDefaults() {
        // Restore default prefixes after each test
        StreamKeys.configure("streaming:mq", "stream:topic");
    }

    // ===== configure Tests =====

    @Test
    void testConfigureWithValidPrefixes() {
        StreamKeys.configure("custom:control", "custom:stream");

        assertEquals("custom:control", StreamKeys.controlPrefix());
        assertEquals("custom:stream", StreamKeys.streamPrefix());
    }

    @Test
    void testConfigureWithNullPrefixes() {
        StreamKeys.configure(null, null);

        // Should keep existing prefixes
        assertNotNull(StreamKeys.controlPrefix());
        assertNotNull(StreamKeys.streamPrefix());
    }

    @Test
    void testConfigureWithEmptyPrefixes() {
        String beforeControl = StreamKeys.controlPrefix();
        String beforeStream = StreamKeys.streamPrefix();

        StreamKeys.configure("", "");

        // Should keep existing prefixes when empty strings provided
        assertEquals(beforeControl, StreamKeys.controlPrefix());
        assertEquals(beforeStream, StreamKeys.streamPrefix());
    }

    @Test
    void testConfigureWithBlankPrefixes() {
        String beforeControl = StreamKeys.controlPrefix();
        String beforeStream = StreamKeys.streamPrefix();

        StreamKeys.configure("   ", "   ");

        // Should keep existing prefixes when blank strings provided
        assertEquals(beforeControl, StreamKeys.controlPrefix());
        assertEquals(beforeStream, StreamKeys.streamPrefix());
    }

    @Test
    void testConfigureWithMixedValidAndInvalid() {
        StreamKeys.configure("new:control", null);

        assertEquals("new:control", StreamKeys.controlPrefix());
        // Stream prefix should remain unchanged
        assertNotNull(StreamKeys.streamPrefix());
    }

    // ===== partitionStream Tests =====

    @Test
    void testPartitionStreamWithDefaults() {
        String key = StreamKeys.partitionStream("my-topic", 0);

        assertEquals("stream:topic:my-topic:p:0", key);
    }

    @Test
    void testPartitionStreamWithDifferentPartition() {
        String key = StreamKeys.partitionStream("my-topic", 5);

        assertEquals("stream:topic:my-topic:p:5", key);
    }

    @Test
    void testPartitionStreamWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.partitionStream("my-topic", 0);

        assertEquals("custom:stream:my-topic:p:0", key);
    }

    @Test
    void testPartitionStreamWithTopicContainingColon() {
        String key = StreamKeys.partitionStream("my:topic", 0);

        assertEquals("stream:topic:my:topic:p:0", key);
    }

    // ===== dlq Tests =====

    @Test
    void testDlqWithDefaults() {
        String key = StreamKeys.dlq("my-topic");

        assertEquals("stream:topic:my-topic:dlq", key);
    }

    @Test
    void testDlqWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.dlq("my-topic");

        assertEquals("custom:stream:my-topic:dlq", key);
    }

    // ===== topicMeta Tests =====

    @Test
    void testTopicMetaWithDefaults() {
        String key = StreamKeys.topicMeta("my-topic");

        assertEquals("streaming:mq:topic:my-topic:meta", key);
    }

    @Test
    void testTopicMetaWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.topicMeta("my-topic");

        assertEquals("custom:control:topic:my-topic:meta", key);
    }

    // ===== topicPartitionsSet Tests =====

    @Test
    void testTopicPartitionsSetWithDefaults() {
        String key = StreamKeys.topicPartitionsSet("my-topic");

        assertEquals("streaming:mq:topic:my-topic:partitions", key);
    }

    @Test
    void testTopicPartitionsSetWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.topicPartitionsSet("my-topic");

        assertEquals("custom:control:topic:my-topic:partitions", key);
    }

    // ===== lease Tests =====

    @Test
    void testLeaseWithDefaults() {
        String key = StreamKeys.lease("my-topic", "my-group", 0);

        assertEquals("streaming:mq:lease:my-topic:my-group:0", key);
    }

    @Test
    void testLeaseWithDifferentPartition() {
        String key = StreamKeys.lease("my-topic", "my-group", 5);

        assertEquals("streaming:mq:lease:my-topic:my-group:5", key);
    }

    @Test
    void testLeaseWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.lease("my-topic", "my-group", 0);

        assertEquals("custom:control:lease:my-topic:my-group:0", key);
    }

    // ===== retryBucket Tests =====

    @Test
    void testRetryBucketWithDefaults() {
        String key = StreamKeys.retryBucket("my-topic");

        assertEquals("streaming:mq:retry:my-topic", key);
    }

    @Test
    void testRetryBucketWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.retryBucket("my-topic");

        assertEquals("custom:control:retry:my-topic", key);
    }

    // ===== retryItem Tests =====

    @Test
    void testRetryItemWithDefaults() {
        String key = StreamKeys.retryItem("my-topic", "msg-id-123");

        assertEquals("streaming:mq:retry:item:my-topic:msg-id-123", key);
    }

    @Test
    void testRetryItemWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.retryItem("my-topic", "msg-id-123");

        assertEquals("custom:control:retry:item:my-topic:msg-id-123", key);
    }

    // ===== topicsRegistry Tests =====

    @Test
    void testTopicsRegistryWithDefaults() {
        String key = StreamKeys.topicsRegistry();

        assertEquals("streaming:mq:topics:registry", key);
    }

    @Test
    void testTopicsRegistryWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.topicsRegistry();

        assertEquals("custom:control:topics:registry", key);
    }

    // ===== commitFrontier Tests =====

    @Test
    void testCommitFrontierWithDefaults() {
        String key = StreamKeys.commitFrontier("my-topic", 0);

        assertEquals("streaming:mq:commit:my-topic:p:0", key);
    }

    @Test
    void testCommitFrontierWithDifferentPartition() {
        String key = StreamKeys.commitFrontier("my-topic", 5);

        assertEquals("streaming:mq:commit:my-topic:p:5", key);
    }

    @Test
    void testCommitFrontierWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.commitFrontier("my-topic", 0);

        assertEquals("custom:control:commit:my-topic:p:0", key);
    }

    // ===== ackSet Tests =====

    @Test
    void testAckSetWithDefaults() {
        String key = StreamKeys.ackSet("my-topic", 0, "msg-id-123");

        assertEquals("streaming:mq:acks:my-topic:p:0:msg-id-123", key);
    }

    @Test
    void testAckSetWithDifferentPartition() {
        String key = StreamKeys.ackSet("my-topic", 5, "msg-id-123");

        assertEquals("streaming:mq:acks:my-topic:p:5:msg-id-123", key);
    }

    @Test
    void testAckSetWithCustomPrefix() {
        StreamKeys.configure("custom:control", "custom:stream");

        String key = StreamKeys.ackSet("my-topic", 0, "msg-id-123");

        assertEquals("custom:control:acks:my-topic:p:0:msg-id-123", key);
    }

    // ===== Edge Cases Tests =====

    @Test
    void testWithEmptyTopic() {
        String key = StreamKeys.partitionStream("", 0);

        // Empty topic results in double colon: "stream:topic::p:0"
        assertEquals("stream:topic::p:0", key);
    }

    @Test
    void testWithTopicContainingSpecialCharacters() {
        String key = StreamKeys.partitionStream("topic-with-特殊字符", 0);

        assertTrue(key.contains("topic-with-特殊字符"));
    }

    @Test
    void testWithNegativePartition() {
        // The method should still work with negative partition IDs
        String key = StreamKeys.partitionStream("my-topic", -1);

        assertTrue(key.contains(":-1"));
    }

    @Test
    void testWithLargePartition() {
        String key = StreamKeys.partitionStream("my-topic", 9999);

        assertEquals("stream:topic:my-topic:p:9999", key);
    }
}
