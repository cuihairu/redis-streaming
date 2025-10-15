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

    // Retry policy
    private int retryMaxAttempts = 5;
    private long retryBaseBackoffMs = 1000;
    private long retryMaxBackoffMs = 60000;

    // Retry mover
    private int retryMoverBatch = 100;
    private int retryMoverIntervalSec = 1;
    private long retryLockWaitMs = 100;
    private long retryLockLeaseMs = 500;

    // Keyspace prefix for Redis keys owned by MQ (to avoid collisions in shared Redis)
    private String keyPrefix = "streaming:mq";

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
        public Builder retryMaxAttempts(int v){ o.retryMaxAttempts = Math.max(1, v); return this; }
        public Builder retryBaseBackoffMs(long v){ o.retryBaseBackoffMs = Math.max(0, v); return this; }
        public Builder retryMaxBackoffMs(long v){ o.retryMaxBackoffMs = Math.max(0, v); return this; }
        public Builder retryMoverBatch(int v){ o.retryMoverBatch = Math.max(1, v); return this; }
        public Builder retryMoverIntervalSec(int v){ o.retryMoverIntervalSec = Math.max(1, v); return this; }
        public Builder retryLockWaitMs(long v){ o.retryLockWaitMs = Math.max(0, v); return this; }
        public Builder retryLockLeaseMs(long v){ o.retryLockLeaseMs = Math.max(0, v); return this; }
        public Builder keyPrefix(String v){ if (v != null && !v.isBlank()) o.keyPrefix = v; return this; }
        public MqOptions build(){ return o; }
    }

}
