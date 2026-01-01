package io.github.cuihairu.redis.streaming.mq.config;

import lombok.Getter;

/**
 * MQ runtime options. Provide sensible defaults and a builder for overrides.
 */
@Getter
public class MqOptions {

    // Partitions
    private int defaultPartitionCount = 1;

    // Threads
    private int workerThreads = 8;
    private int schedulerThreads = 2;

    // Consumer read
    private int consumerBatchCount = 10;
    private long consumerPollTimeoutMs = 1000;

    // Leases & rebalance
    private int leaseTtlSeconds = 15;
    private int rebalanceIntervalSec = 5;
    private int renewIntervalSec = 3;
    private int pendingScanIntervalSec = 30;

    // Pending claim
    private long claimIdleMs = 300000; // 5 minutes
    private int claimBatchSize = 50;

    // Backpressure (global per consumer instance). 0 disables.
    private int maxInFlight = 0;

    // Partition leasing: cap active leased partitions per consumer instance. 0 means default to workerThreads.
    private int maxLeasedPartitionsPerConsumer = 0;

    // Retry policy
    private int retryMaxAttempts = 5;
    private long retryBaseBackoffMs = 1000;
    private long retryMaxBackoffMs = 60000;

    // Retry mover
    private int retryMoverBatch = 100;
    private int retryMoverIntervalSec = 1;
    private long retryLockWaitMs = 100;
    private long retryLockLeaseMs = 500;

    // Keyspace prefixes for Redis keys owned by MQ (to avoid collisions in shared Redis)
    private String keyPrefix = "streaming:mq";      // control keys (meta/lease/retry)
    private String streamKeyPrefix = "stream:topic"; // data streams (partitions/DLQ)

    // Naming conventions
    private String consumerNamePrefix = "consumer-";
    private String dlqConsumerSuffix = "-dlq";
    private String defaultConsumerGroup = "default-group";
    private String defaultDlqGroup = "dlq-group";

    // Retention (Streams) - low overhead defaults
    // Per-partition maximum length for stream (approximate trimming when possible)
    private int retentionMaxLenPerPartition = 100_000; // default bounded backlog
    // Optional time-based retention (milliseconds). 0 disables time trimming.
    private long retentionMs = 0L;
    // Background trimming cadence (seconds)
    private int trimIntervalSec = 60;

    // DLQ-specific retention (overrides if >0)
    private int dlqRetentionMaxLen = 0; // 0 means use main retention if set
    private long dlqRetentionMs = 0L;   // 0 means disabled

    // Deletion policy on ACK: none | immediate | all-groups-ack
    private String ackDeletePolicy = "none";
    // TTL for ack-set keys used by all-groups-ack strategy (seconds)
    private int acksetTtlSec = 86400; // 1 day

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final MqOptions o = new MqOptions();
        public Builder defaultPartitionCount(int v){ o.defaultPartitionCount = Math.max(1, v); return this; }
        public Builder workerThreads(int v){ o.workerThreads = Math.max(1, v); return this; }
        public Builder schedulerThreads(int v){ o.schedulerThreads = Math.max(1, v); return this; }
        public Builder consumerBatchCount(int v){ o.consumerBatchCount = Math.max(1, v); return this; }
        public Builder consumerPollTimeoutMs(long v){ o.consumerPollTimeoutMs = Math.max(0, v); return this; }
        public Builder leaseTtlSeconds(int v){ o.leaseTtlSeconds = Math.max(1, v); return this; }
        public Builder rebalanceIntervalSec(int v){ o.rebalanceIntervalSec = Math.max(1, v); return this; }
        public Builder renewIntervalSec(int v){ o.renewIntervalSec = Math.max(1, v); return this; }
        public Builder pendingScanIntervalSec(int v){ o.pendingScanIntervalSec = Math.max(1, v); return this; }
        public Builder claimIdleMs(long v){ o.claimIdleMs = Math.max(0, v); return this; }
        public Builder claimBatchSize(int v){ o.claimBatchSize = Math.max(1, v); return this; }
        public Builder maxInFlight(int v){ o.maxInFlight = Math.max(0, v); return this; }
        public Builder maxLeasedPartitionsPerConsumer(int v){ o.maxLeasedPartitionsPerConsumer = Math.max(0, v); return this; }
        public Builder retryMaxAttempts(int v){ o.retryMaxAttempts = Math.max(1, v); return this; }
        public Builder retryBaseBackoffMs(long v){ o.retryBaseBackoffMs = Math.max(0, v); return this; }
        public Builder retryMaxBackoffMs(long v){ o.retryMaxBackoffMs = Math.max(0, v); return this; }
        public Builder retryMoverBatch(int v){ o.retryMoverBatch = Math.max(1, v); return this; }
        public Builder retryMoverIntervalSec(int v){ o.retryMoverIntervalSec = Math.max(1, v); return this; }
        public Builder retryLockWaitMs(long v){ o.retryLockWaitMs = Math.max(0, v); return this; }
        public Builder retryLockLeaseMs(long v){ o.retryLockLeaseMs = Math.max(0, v); return this; }
        public Builder keyPrefix(String v){ if (v != null && !v.isBlank()) o.keyPrefix = v; return this; }
        public Builder streamKeyPrefix(String v){ if (v != null && !v.isBlank()) o.streamKeyPrefix = v; return this; }
        public Builder consumerNamePrefix(String v){ if (v != null && !v.isBlank()) o.consumerNamePrefix = v; return this; }
        public Builder dlqConsumerSuffix(String v){ if (v != null) o.dlqConsumerSuffix = v; return this; }
        public Builder defaultConsumerGroup(String v){ if (v != null && !v.isBlank()) o.defaultConsumerGroup = v; return this; }
        public Builder defaultDlqGroup(String v){ if (v != null && !v.isBlank()) o.defaultDlqGroup = v; return this; }
        public Builder retentionMaxLenPerPartition(int v){ o.retentionMaxLenPerPartition = Math.max(0, v); return this; }
        public Builder retentionMs(long v){ o.retentionMs = Math.max(0, v); return this; }
        public Builder trimIntervalSec(int v){ o.trimIntervalSec = Math.max(1, v); return this; }
        public Builder dlqRetentionMaxLen(int v){ o.dlqRetentionMaxLen = Math.max(0, v); return this; }
        public Builder dlqRetentionMs(long v){ o.dlqRetentionMs = Math.max(0, v); return this; }
        public Builder ackDeletePolicy(String v){ if (v != null && !v.isBlank()) o.ackDeletePolicy = v.toLowerCase(); return this; }
        public Builder acksetTtlSec(int v){ o.acksetTtlSec = Math.max(1, v); return this; }
        public MqOptions build(){ return o; }
    }

}
