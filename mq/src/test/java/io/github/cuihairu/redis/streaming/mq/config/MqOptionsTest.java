package io.github.cuihairu.redis.streaming.mq.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MqOptions
 */
class MqOptionsTest {

    @Test
    void testDefaultValues() {
        // Given & When
        MqOptions options = MqOptions.builder().build();

        // Then - Partitions
        assertEquals(1, options.getDefaultPartitionCount());

        // Then - Threads
        assertEquals(8, options.getWorkerThreads());
        assertEquals(2, options.getSchedulerThreads());

        // Then - Consumer read
        assertEquals(10, options.getConsumerBatchCount());
        assertEquals(1000, options.getConsumerPollTimeoutMs());

        // Then - Leases & rebalance
        assertEquals(15, options.getLeaseTtlSeconds());
        assertEquals(5, options.getRebalanceIntervalSec());
        assertEquals(3, options.getRenewIntervalSec());
        assertEquals(30, options.getPendingScanIntervalSec());

        // Then - Pending claim
        assertEquals(300000, options.getClaimIdleMs());
        assertEquals(50, options.getClaimBatchSize());

        // Then - Retry policy
        assertEquals(5, options.getRetryMaxAttempts());
        assertEquals(1000, options.getRetryBaseBackoffMs());
        assertEquals(60000, options.getRetryMaxBackoffMs());

        // Then - Retry mover
        assertEquals(100, options.getRetryMoverBatch());
        assertEquals(1, options.getRetryMoverIntervalSec());
        assertEquals(100, options.getRetryLockWaitMs());
        assertEquals(500, options.getRetryLockLeaseMs());

        // Then - Keyspace prefixes
        assertEquals("streaming:mq", options.getKeyPrefix());
        assertEquals("stream:topic", options.getStreamKeyPrefix());

        // Then - Naming conventions
        assertEquals("consumer-", options.getConsumerNamePrefix());
        assertEquals("-dlq", options.getDlqConsumerSuffix());
        assertEquals("default-group", options.getDefaultConsumerGroup());
        assertEquals("dlq-group", options.getDefaultDlqGroup());

        // Then - Retention
        assertEquals(100_000, options.getRetentionMaxLenPerPartition());
        assertEquals(0, options.getRetentionMs());
        assertEquals(60, options.getTrimIntervalSec());

        // Then - DLQ retention
        assertEquals(0, options.getDlqRetentionMaxLen());
        assertEquals(0, options.getDlqRetentionMs());

        // Then - ACK delete policy
        assertEquals("none", options.getAckDeletePolicy());
        assertEquals(86400, options.getAcksetTtlSec());
    }

    @Test
    void testBuilderWithDefaultPartitionCount() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultPartitionCount(5)
            .build();

        // Then
        assertEquals(5, options.getDefaultPartitionCount());
    }

    @Test
    void testBuilderWithZeroPartitionCountIsClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultPartitionCount(0)
            .build();

        // Then - should be clamped to minimum 1
        assertEquals(1, options.getDefaultPartitionCount());
    }

    @Test
    void testBuilderWithNegativePartitionCountIsClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultPartitionCount(-10)
            .build();

        // Then - should be clamped to minimum 1
        assertEquals(1, options.getDefaultPartitionCount());
    }

    @Test
    void testBuilderWithWorkerThreads() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .workerThreads(16)
            .build();

        // Then
        assertEquals(16, options.getWorkerThreads());
    }

    @Test
    void testBuilderWithZeroWorkerThreadsIsClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .workerThreads(0)
            .build();

        // Then
        assertEquals(1, options.getWorkerThreads());
    }

    @Test
    void testBuilderWithSchedulerThreads() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .schedulerThreads(4)
            .build();

        // Then
        assertEquals(4, options.getSchedulerThreads());
    }

    @Test
    void testBuilderWithConsumerBatchCount() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerBatchCount(50)
            .build();

        // Then
        assertEquals(50, options.getConsumerBatchCount());
    }

    @Test
    void testBuilderWithConsumerPollTimeoutMs() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerPollTimeoutMs(5000)
            .build();

        // Then
        assertEquals(5000, options.getConsumerPollTimeoutMs());
    }

    @Test
    void testBuilderWithNegativePollTimeoutIsClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerPollTimeoutMs(-100)
            .build();

        // Then
        assertEquals(0, options.getConsumerPollTimeoutMs());
    }

    @Test
    void testBuilderWithLeaseTtlSeconds() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .leaseTtlSeconds(30)
            .build();

        // Then
        assertEquals(30, options.getLeaseTtlSeconds());
    }

    @Test
    void testBuilderWithKeyPrefix() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .keyPrefix("custom:prefix")
            .build();

        // Then
        assertEquals("custom:prefix", options.getKeyPrefix());
    }

    @Test
    void testBuilderWithNullKeyPrefixUsesDefault() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .keyPrefix(null)
            .build();

        // Then - should keep default
        assertEquals("streaming:mq", options.getKeyPrefix());
    }

    @Test
    void testBuilderWithBlankKeyPrefixUsesDefault() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .keyPrefix("   ")
            .build();

        // Then - should keep default
        assertEquals("streaming:mq", options.getKeyPrefix());
    }

    @Test
    void testBuilderWithStreamKeyPrefix() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .streamKeyPrefix("custom:stream")
            .build();

        // Then
        assertEquals("custom:stream", options.getStreamKeyPrefix());
    }

    @Test
    void testBuilderWithConsumerNamePrefix() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerNamePrefix("my-consumer-")
            .build();

        // Then
        assertEquals("my-consumer-", options.getConsumerNamePrefix());
    }

    @Test
    void testBuilderWithDlqConsumerSuffix() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqConsumerSuffix("-dead")
            .build();

        // Then
        assertEquals("-dead", options.getDlqConsumerSuffix());
    }

    @Test
    void testBuilderWithNullDlqConsumerSuffix() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqConsumerSuffix(null)
            .build();

        // Then - null is allowed for DLQ suffix, builder checks "if (v != null)"
        // Since the check is "if (v != null)", null doesn't change the default value
        assertEquals("-dlq", options.getDlqConsumerSuffix());
    }

    @Test
    void testBuilderWithDefaultConsumerGroup() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultConsumerGroup("my-group")
            .build();

        // Then
        assertEquals("my-group", options.getDefaultConsumerGroup());
    }

    @Test
    void testBuilderWithDefaultDlqGroup() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultDlqGroup("dead-letter-group")
            .build();

        // Then
        assertEquals("dead-letter-group", options.getDefaultDlqGroup());
    }

    @Test
    void testBuilderWithRetentionMaxLenPerPartition() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retentionMaxLenPerPartition(1_000_000)
            .build();

        // Then
        assertEquals(1_000_000, options.getRetentionMaxLenPerPartition());
    }

    @Test
    void testBuilderWithZeroRetentionMaxLen() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retentionMaxLenPerPartition(0)
            .build();

        // Then - 0 is allowed (unbounded)
        assertEquals(0, options.getRetentionMaxLenPerPartition());
    }

    @Test
    void testBuilderWithRetentionMs() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retentionMs(3600000) // 1 hour
            .build();

        // Then
        assertEquals(3600000, options.getRetentionMs());
    }

    @Test
    void testBuilderWithDlqRetentionMaxLen() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqRetentionMaxLen(50000)
            .build();

        // Then
        assertEquals(50000, options.getDlqRetentionMaxLen());
    }

    @Test
    void testBuilderWithDlqRetentionMs() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqRetentionMs(7200000) // 2 hours
            .build();

        // Then
        assertEquals(7200000, options.getDlqRetentionMs());
    }

    @Test
    void testBuilderWithAckDeletePolicy() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .ackDeletePolicy("IMMEDIATE")
            .build();

        // Then - should be converted to lowercase
        assertEquals("immediate", options.getAckDeletePolicy());
    }

    @Test
    void testBuilderWithAcksetTtlSec() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .acksetTtlSec(3600)
            .build();

        // Then
        assertEquals(3600, options.getAcksetTtlSec());
    }

    @Test
    void testBuilderWithRetryMaxAttempts() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMaxAttempts(10)
            .build();

        // Then
        assertEquals(10, options.getRetryMaxAttempts());
    }

    @Test
    void testBuilderWithRetryBaseBackoffMs() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryBaseBackoffMs(2000)
            .build();

        // Then
        assertEquals(2000, options.getRetryBaseBackoffMs());
    }

    @Test
    void testBuilderWithRetryMaxBackoffMs() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMaxBackoffMs(120000)
            .build();

        // Then
        assertEquals(120000, options.getRetryMaxBackoffMs());
    }

    @Test
    void testBuilderWithMultipleOptions() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultPartitionCount(3)
            .workerThreads(12)
            .consumerBatchCount(20)
            .keyPrefix("test:mq")
            .streamKeyPrefix("test:stream")
            .consumerNamePrefix("client-")
            .dlqConsumerSuffix("-dlq-test")
            .retentionMaxLenPerPartition(50000)
            .retryMaxAttempts(7)
            .build();

        // Then
        assertEquals(3, options.getDefaultPartitionCount());
        assertEquals(12, options.getWorkerThreads());
        assertEquals(20, options.getConsumerBatchCount());
        assertEquals("test:mq", options.getKeyPrefix());
        assertEquals("test:stream", options.getStreamKeyPrefix());
        assertEquals("client-", options.getConsumerNamePrefix());
        assertEquals("-dlq-test", options.getDlqConsumerSuffix());
        assertEquals(50000, options.getRetentionMaxLenPerPartition());
        assertEquals(7, options.getRetryMaxAttempts());
    }

    @Test
    void testBuilderReturnsNewInstanceEachTime() {
        // Given & When
        MqOptions options1 = MqOptions.builder()
            .defaultPartitionCount(5)
            .build();

        MqOptions options2 = MqOptions.builder()
            .defaultPartitionCount(10)
            .build();

        // Then - each builder creates independent instance
        assertEquals(5, options1.getDefaultPartitionCount());
        assertEquals(10, options2.getDefaultPartitionCount());
    }

    @Test
    void testBuilderChaining() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .workerThreads(4)
            .schedulerThreads(1)
            .consumerBatchCount(25)
            .leaseTtlSeconds(20)
            .build();

        // Then - builder should support chaining
        assertEquals(4, options.getWorkerThreads());
        assertEquals(1, options.getSchedulerThreads());
        assertEquals(25, options.getConsumerBatchCount());
        assertEquals(20, options.getLeaseTtlSeconds());
    }

    @Test
    void testBuilderWithClaimIdleMs() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .claimIdleMs(600000) // 10 minutes
            .build();

        // Then
        assertEquals(600000, options.getClaimIdleMs());
    }

    @Test
    void testBuilderWithClaimBatchSize() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .claimBatchSize(100)
            .build();

        // Then
        assertEquals(100, options.getClaimBatchSize());
    }

    @Test
    void testBuilderWithRetryMoverBatch() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMoverBatch(200)
            .build();

        // Then
        assertEquals(200, options.getRetryMoverBatch());
    }

    @Test
    void testBuilderWithTrimIntervalSec() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .trimIntervalSec(120)
            .build();

        // Then
        assertEquals(120, options.getTrimIntervalSec());
    }
}
