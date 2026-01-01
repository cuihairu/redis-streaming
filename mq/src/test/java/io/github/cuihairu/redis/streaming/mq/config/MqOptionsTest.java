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

    // ==================== Additional Branch Coverage Tests ====================

    @Test
    void testBuilderWithRebalanceIntervalSec() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .rebalanceIntervalSec(10)
            .build();

        // Then
        assertEquals(10, options.getRebalanceIntervalSec());
    }

    @Test
    void testBuilderWithRebalanceIntervalSecZeroClampedToOne() {
        // Given & When - branch: Math.max(1, v) with v=0
        MqOptions options = MqOptions.builder()
            .rebalanceIntervalSec(0)
            .build();

        // Then
        assertEquals(1, options.getRebalanceIntervalSec());
    }

    @Test
    void testBuilderWithRenewIntervalSec() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .renewIntervalSec(2)
            .build();

        // Then
        assertEquals(2, options.getRenewIntervalSec());
    }

    @Test
    void testBuilderWithRenewIntervalSecZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .renewIntervalSec(0)
            .build();

        // Then
        assertEquals(1, options.getRenewIntervalSec());
    }

    @Test
    void testBuilderWithPendingScanIntervalSec() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .pendingScanIntervalSec(60)
            .build();

        // Then
        assertEquals(60, options.getPendingScanIntervalSec());
    }

    @Test
    void testBuilderWithPendingScanIntervalSecZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .pendingScanIntervalSec(0)
            .build();

        // Then
        assertEquals(1, options.getPendingScanIntervalSec());
    }

    @Test
    void testBuilderWithClaimIdleMsZeroAllowed() {
        // Given & When - branch: Math.max(0, v) allows 0
        MqOptions options = MqOptions.builder()
            .claimIdleMs(0)
            .build();

        // Then
        assertEquals(0, options.getClaimIdleMs());
    }

    @Test
    void testBuilderWithClaimIdleMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .claimIdleMs(-1000)
            .build();

        // Then
        assertEquals(0, options.getClaimIdleMs());
    }

    @Test
    void testBuilderWithClaimBatchSizeZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .claimBatchSize(0)
            .build();

        // Then
        assertEquals(1, options.getClaimBatchSize());
    }

    @Test
    void testBuilderWithMaxInFlightPositive() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .maxInFlight(1000)
            .build();

        // Then
        assertEquals(1000, options.getMaxInFlight());
    }

    @Test
    void testBuilderWithMaxInFlightZeroAllowed() {
        // Given & When - branch: Math.max(0, v) allows 0 (disabled)
        MqOptions options = MqOptions.builder()
            .maxInFlight(0)
            .build();

        // Then
        assertEquals(0, options.getMaxInFlight());
    }

    @Test
    void testBuilderWithMaxInFlightNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .maxInFlight(-100)
            .build();

        // Then
        assertEquals(0, options.getMaxInFlight());
    }

    @Test
    void testBuilderWithMaxLeasedPartitionsPerConsumerPositive() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .maxLeasedPartitionsPerConsumer(16)
            .build();

        // Then
        assertEquals(16, options.getMaxLeasedPartitionsPerConsumer());
    }

    @Test
    void testBuilderWithMaxLeasedPartitionsPerConsumerZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .maxLeasedPartitionsPerConsumer(0)
            .build();

        // Then
        assertEquals(0, options.getMaxLeasedPartitionsPerConsumer());
    }

    @Test
    void testBuilderWithMaxLeasedPartitionsPerConsumerNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .maxLeasedPartitionsPerConsumer(-5)
            .build();

        // Then
        assertEquals(0, options.getMaxLeasedPartitionsPerConsumer());
    }

    @Test
    void testBuilderWithRetryMaxAttemptsZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMaxAttempts(0)
            .build();

        // Then
        assertEquals(1, options.getRetryMaxAttempts());
    }

    @Test
    void testBuilderWithRetryBaseBackoffMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryBaseBackoffMs(0)
            .build();

        // Then
        assertEquals(0, options.getRetryBaseBackoffMs());
    }

    @Test
    void testBuilderWithRetryBaseBackoffMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryBaseBackoffMs(-500)
            .build();

        // Then
        assertEquals(0, options.getRetryBaseBackoffMs());
    }

    @Test
    void testBuilderWithRetryMaxBackoffMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMaxBackoffMs(0)
            .build();

        // Then
        assertEquals(0, options.getRetryMaxBackoffMs());
    }

    @Test
    void testBuilderWithRetryMaxBackoffMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMaxBackoffMs(-1000)
            .build();

        // Then
        assertEquals(0, options.getRetryMaxBackoffMs());
    }

    @Test
    void testBuilderWithRetryMoverBatchZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMoverBatch(0)
            .build();

        // Then
        assertEquals(1, options.getRetryMoverBatch());
    }

    @Test
    void testBuilderWithRetryMoverIntervalSec() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMoverIntervalSec(5)
            .build();

        // Then
        assertEquals(5, options.getRetryMoverIntervalSec());
    }

    @Test
    void testBuilderWithRetryMoverIntervalSecZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryMoverIntervalSec(0)
            .build();

        // Then
        assertEquals(1, options.getRetryMoverIntervalSec());
    }

    @Test
    void testBuilderWithRetryLockWaitMsPositive() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryLockWaitMs(200)
            .build();

        // Then
        assertEquals(200, options.getRetryLockWaitMs());
    }

    @Test
    void testBuilderWithRetryLockWaitMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryLockWaitMs(0)
            .build();

        // Then
        assertEquals(0, options.getRetryLockWaitMs());
    }

    @Test
    void testBuilderWithRetryLockWaitMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryLockWaitMs(-50)
            .build();

        // Then
        assertEquals(0, options.getRetryLockWaitMs());
    }

    @Test
    void testBuilderWithRetryLockLeaseMsPositive() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryLockLeaseMs(1000)
            .build();

        // Then
        assertEquals(1000, options.getRetryLockLeaseMs());
    }

    @Test
    void testBuilderWithRetryLockLeaseMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryLockLeaseMs(0)
            .build();

        // Then
        assertEquals(0, options.getRetryLockLeaseMs());
    }

    @Test
    void testBuilderWithRetryLockLeaseMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retryLockLeaseMs(-100)
            .build();

        // Then
        assertEquals(0, options.getRetryLockLeaseMs());
    }

    @Test
    void testBuilderWithKeyPrefixEmptyStringIgnored() {
        // Given & When - branch: v.isBlank() with empty string
        MqOptions options = MqOptions.builder()
            .keyPrefix("")
            .build();

        // Then
        assertEquals("streaming:mq", options.getKeyPrefix());
    }

    @Test
    void testBuilderWithStreamKeyPrefixNullIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .streamKeyPrefix(null)
            .build();

        // Then
        assertEquals("stream:topic", options.getStreamKeyPrefix());
    }

    @Test
    void testBuilderWithStreamKeyPrefixEmptyIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .streamKeyPrefix("")
            .build();

        // Then
        assertEquals("stream:topic", options.getStreamKeyPrefix());
    }

    @Test
    void testBuilderWithStreamKeyPrefixWhitespaceIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .streamKeyPrefix("  \t\n  ")
            .build();

        // Then
        assertEquals("stream:topic", options.getStreamKeyPrefix());
    }

    @Test
    void testBuilderWithConsumerNamePrefixNullIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerNamePrefix(null)
            .build();

        // Then
        assertEquals("consumer-", options.getConsumerNamePrefix());
    }

    @Test
    void testBuilderWithConsumerNamePrefixEmptyIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerNamePrefix("")
            .build();

        // Then
        assertEquals("consumer-", options.getConsumerNamePrefix());
    }

    @Test
    void testBuilderWithDlqConsumerSuffixEmptyAllowed() {
        // Given & When - branch: v != null allows empty string (special case)
        MqOptions options = MqOptions.builder()
            .dlqConsumerSuffix("")
            .build();

        // Then - empty string is allowed for DLQ suffix
        assertEquals("", options.getDlqConsumerSuffix());
    }

    @Test
    void testBuilderWithDefaultConsumerGroupNullIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultConsumerGroup(null)
            .build();

        // Then
        assertEquals("default-group", options.getDefaultConsumerGroup());
    }

    @Test
    void testBuilderWithDefaultConsumerGroupEmptyIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultConsumerGroup("")
            .build();

        // Then
        assertEquals("default-group", options.getDefaultConsumerGroup());
    }

    @Test
    void testBuilderWithDefaultDlqGroupNullIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultDlqGroup(null)
            .build();

        // Then
        assertEquals("dlq-group", options.getDefaultDlqGroup());
    }

    @Test
    void testBuilderWithDefaultDlqGroupEmptyIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .defaultDlqGroup("")
            .build();

        // Then
        assertEquals("dlq-group", options.getDefaultDlqGroup());
    }

    @Test
    void testBuilderWithRetentionMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retentionMs(0)
            .build();

        // Then
        assertEquals(0, options.getRetentionMs());
    }

    @Test
    void testBuilderWithRetentionMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .retentionMs(-1000)
            .build();

        // Then
        assertEquals(0, options.getRetentionMs());
    }

    @Test
    void testBuilderWithTrimIntervalSecZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .trimIntervalSec(0)
            .build();

        // Then
        assertEquals(1, options.getTrimIntervalSec());
    }

    @Test
    void testBuilderWithDlqRetentionMaxLenPositive() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqRetentionMaxLen(10000)
            .build();

        // Then
        assertEquals(10000, options.getDlqRetentionMaxLen());
    }

    @Test
    void testBuilderWithDlqRetentionMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqRetentionMs(0)
            .build();

        // Then
        assertEquals(0, options.getDlqRetentionMs());
    }

    @Test
    void testBuilderWithDlqRetentionMsNegativeClampedToZero() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .dlqRetentionMs(-5000)
            .build();

        // Then
        assertEquals(0, options.getDlqRetentionMs());
    }

    @Test
    void testBuilderWithAckDeletePolicyMixedCaseToLowercase() {
        // Given & When - branch: toLowerCase() with mixed case
        MqOptions options = MqOptions.builder()
            .ackDeletePolicy("ImMedIate")
            .build();

        // Then
        assertEquals("immediate", options.getAckDeletePolicy());
    }

    @Test
    void testBuilderWithAckDeletePolicyNullIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .ackDeletePolicy(null)
            .build();

        // Then
        assertEquals("none", options.getAckDeletePolicy());
    }

    @Test
    void testBuilderWithAckDeletePolicyEmptyIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .ackDeletePolicy("")
            .build();

        // Then
        assertEquals("none", options.getAckDeletePolicy());
    }

    @Test
    void testBuilderWithAckDeletePolicyWhitespaceIgnored() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .ackDeletePolicy("   ")
            .build();

        // Then
        assertEquals("none", options.getAckDeletePolicy());
    }

    @Test
    void testBuilderWithAcksetTtlSecZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .acksetTtlSec(0)
            .build();

        // Then
        assertEquals(1, options.getAcksetTtlSec());
    }

    @Test
    void testBuilderWithAcksetTtlSecNegativeClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .acksetTtlSec(-100)
            .build();

        // Then
        assertEquals(1, options.getAcksetTtlSec());
    }

    @Test
    void testBuilderWithLeaseTtlSecondsZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .leaseTtlSeconds(0)
            .build();

        // Then
        assertEquals(1, options.getLeaseTtlSeconds());
    }

    @Test
    void testBuilderWithSchedulerThreadsZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .schedulerThreads(0)
            .build();

        // Then
        assertEquals(1, options.getSchedulerThreads());
    }

    @Test
    void testBuilderWithConsumerBatchCountZeroClampedToOne() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerBatchCount(0)
            .build();

        // Then
        assertEquals(1, options.getConsumerBatchCount());
    }

    @Test
    void testBuilderWithConsumerPollTimeoutMsZeroAllowed() {
        // Given & When
        MqOptions options = MqOptions.builder()
            .consumerPollTimeoutMs(0)
            .build();

        // Then
        assertEquals(0, options.getConsumerPollTimeoutMs());
    }
}
