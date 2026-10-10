package io.github.cuihairu.redis.streaming.mq.config;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * MQ runtime options. Provide sensible defaults and a builder for overrides.
 */
@Getter
public class MqOptions {

    // Tenant namespace (docs/Multi-Tenancy-Design.md). "default" disables the key segment.
    private String tenant = "default";

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

    // Production rate quota (docs/Multi-Tenancy-Design.md step 2): checked before every
    // append with key tenant:topic; a rejected send fails fast with SendRateLimitedException
    // and is counted by MqMetrics.incRateLimited. Null disables the quota.
    private transient SendQuota sendQuota;

    // Retention cap overrides per tenant: tenant -> maxLenPerPartition. Topics of a tenant
    // present in this map are trimmed to their cap instead of retentionMaxLenPerPartition
    // (0 disables length trimming for that tenant). Applied by retention housekeeping.
    private Map<String, Integer> tenantRetentionMaxLenPerPartition = new HashMap<>();

    public static Builder builder() { return new Builder(); }

    /**
     * A copy of these options with the given tenant stamped on (docs/Multi-Tenancy-Design.md).
     * All other fields are carried over unchanged.
     */
    public MqOptions withTenant(String tenant) {
        return new Builder(this).tenant(tenant).build();
    }

    public static class Builder {
        private final MqOptions o = new MqOptions();

        public Builder() {
        }

        /** Copy constructor: starts from an existing options instance. */
        public Builder(MqOptions src) {
            if (src == null) return;
            o.tenant = src.tenant;
            o.defaultPartitionCount = src.defaultPartitionCount;
            o.workerThreads = src.workerThreads;
            o.schedulerThreads = src.schedulerThreads;
            o.consumerBatchCount = src.consumerBatchCount;
            o.consumerPollTimeoutMs = src.consumerPollTimeoutMs;
            o.leaseTtlSeconds = src.leaseTtlSeconds;
            o.rebalanceIntervalSec = src.rebalanceIntervalSec;
            o.renewIntervalSec = src.renewIntervalSec;
            o.pendingScanIntervalSec = src.pendingScanIntervalSec;
            o.claimIdleMs = src.claimIdleMs;
            o.claimBatchSize = src.claimBatchSize;
            o.maxInFlight = src.maxInFlight;
            o.maxLeasedPartitionsPerConsumer = src.maxLeasedPartitionsPerConsumer;
            o.retryMaxAttempts = src.retryMaxAttempts;
            o.retryBaseBackoffMs = src.retryBaseBackoffMs;
            o.retryMaxBackoffMs = src.retryMaxBackoffMs;
            o.retryMoverBatch = src.retryMoverBatch;
            o.retryMoverIntervalSec = src.retryMoverIntervalSec;
            o.retryLockWaitMs = src.retryLockWaitMs;
            o.retryLockLeaseMs = src.retryLockLeaseMs;
            o.keyPrefix = src.keyPrefix;
            o.streamKeyPrefix = src.streamKeyPrefix;
            o.consumerNamePrefix = src.consumerNamePrefix;
            o.dlqConsumerSuffix = src.dlqConsumerSuffix;
            o.defaultConsumerGroup = src.defaultConsumerGroup;
            o.defaultDlqGroup = src.defaultDlqGroup;
            o.retentionMaxLenPerPartition = src.retentionMaxLenPerPartition;
            o.retentionMs = src.retentionMs;
            o.trimIntervalSec = src.trimIntervalSec;
            o.dlqRetentionMaxLen = src.dlqRetentionMaxLen;
            o.dlqRetentionMs = src.dlqRetentionMs;
            o.ackDeletePolicy = src.ackDeletePolicy;
            o.acksetTtlSec = src.acksetTtlSec;
            o.sendQuota = src.sendQuota;
            o.tenantRetentionMaxLenPerPartition = new HashMap<>(src.tenantRetentionMaxLenPerPartition);
        }
        public Builder tenant(String v){ o.tenant = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.normalizeTenant(v); return this; }
        public Builder defaultPartitionCount(int v){ o.defaultPartitionCount = Math.max(1, v); return this; }
        public Builder workerThreads(int v){ o.workerThreads = Math.max(1, v); return this; }
        public Builder schedulerThreads(int v){ o.schedulerThreads = Math.max(1, v); return this; }
        public Builder consumerBatchCount(int v){ o.consumerBatchCount = Math.max(1, v); return this; }
        public Builder consumerPollTimeoutMs(long v){ o.consumerPollTimeoutMs = Math.max(0, v); return this; }
        public Builder leaseTtlSeconds(int v){ o.leaseTtlSeconds = Math.max(1, v); return this; }
        public Builder rebalanceIntervalSec(int v){ o.rebalanceIntervalSec = Math.max(1, v); return this; }
        public Builder renewIntervalSec(int v){ o.renewIntervalSec = Math.max(1, v); return this; }
        public Builder pendingScanIntervalSec(int v){ o.pendingScanIntervalSec = Math.max(1, v); return this; }
        public Builder claimIdleMs(long v){ o.claimIdleMs = Math.max(1, v); return this; }
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
        public Builder sendQuota(SendQuota v){ o.sendQuota = v; return this; }
        /** Per-tenant retention cap override (0 disables length trimming for that tenant). */
        public Builder tenantRetentionMaxLenPerPartition(String tenant, int maxLen){
            o.tenantRetentionMaxLenPerPartition.put(
                    io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.normalizeTenant(tenant), Math.max(0, maxLen));
            return this;
        }
        public MqOptions build(){ return o; }
    }

}
