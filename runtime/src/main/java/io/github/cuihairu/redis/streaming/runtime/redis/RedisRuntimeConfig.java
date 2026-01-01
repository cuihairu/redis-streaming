package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the Redis-backed runtime.
 *
 * <p>This runtime is single-process but uses Redis Streams consumer groups for distributed-safe
 * message consumption and at-least-once delivery.</p>
 */
public final class RedisRuntimeConfig {

    public enum StateSchemaMismatchPolicy {
        FAIL,
        CLEAR,
        IGNORE
    }

    private final String jobName;
    private final String jobInstanceId;
    private final String stateKeyPrefix;
    private final Duration stateTtl;
    private final int stateSizeReportEveryNStateWrites;
    private final int keyedStateShardCount;
    private final long keyedStateHotKeyFieldsWarnThreshold;
    private final Duration keyedStateHotKeyWarnInterval;
    private final boolean stateSchemaEvolutionEnabled;
    private final StateSchemaMismatchPolicy stateSchemaMismatchPolicy;
    private final boolean restoreConsumerGroupFromCommitFrontier;
    private final boolean sinkDeduplicationEnabled;
    private final Duration sinkDeduplicationTtl;
    private final String sinkDedupKeyPrefix;
    private final boolean deferAckUntilCheckpoint;
    private final Duration checkpointInterval;
    private final boolean restoreFromLatestCheckpoint;
    private final String checkpointKeyPrefix;
    private final int checkpointsToKeep;
    private final Duration checkpointDrainTimeout;
    private final MqOptions mqOptions;
    private final MessageHandleResult processingErrorResult;

    private RedisRuntimeConfig(Builder b) {
        this.jobName = Objects.requireNonNull(b.jobName, "jobName");
        this.jobInstanceId = Objects.requireNonNull(b.jobInstanceId, "jobInstanceId");
        this.stateKeyPrefix = Objects.requireNonNull(b.stateKeyPrefix, "stateKeyPrefix");
        this.stateTtl = b.stateTtl == null ? Duration.ZERO : b.stateTtl;
        if (b.stateSizeReportEveryNStateWrites < 0) {
            throw new IllegalArgumentException("stateSizeReportEveryNStateWrites must be >= 0");
        }
        this.stateSizeReportEveryNStateWrites = b.stateSizeReportEveryNStateWrites;
        if (b.keyedStateShardCount <= 0) {
            throw new IllegalArgumentException("keyedStateShardCount must be >= 1");
        }
        this.keyedStateShardCount = b.keyedStateShardCount;
        if (b.keyedStateHotKeyFieldsWarnThreshold < 0) {
            throw new IllegalArgumentException("keyedStateHotKeyFieldsWarnThreshold must be >= 0");
        }
        this.keyedStateHotKeyFieldsWarnThreshold = b.keyedStateHotKeyFieldsWarnThreshold;
        this.keyedStateHotKeyWarnInterval = b.keyedStateHotKeyWarnInterval == null ? Duration.ofMinutes(1) : b.keyedStateHotKeyWarnInterval;
        this.stateSchemaEvolutionEnabled = b.stateSchemaEvolutionEnabled;
        this.stateSchemaMismatchPolicy = b.stateSchemaMismatchPolicy == null ? StateSchemaMismatchPolicy.FAIL : b.stateSchemaMismatchPolicy;
        this.restoreConsumerGroupFromCommitFrontier = b.restoreConsumerGroupFromCommitFrontier;
        this.sinkDeduplicationEnabled = b.sinkDeduplicationEnabled;
        this.sinkDeduplicationTtl = b.sinkDeduplicationTtl == null ? Duration.ofDays(7) : b.sinkDeduplicationTtl;
        this.sinkDedupKeyPrefix = Objects.requireNonNull(b.sinkDedupKeyPrefix, "sinkDedupKeyPrefix");
        this.deferAckUntilCheckpoint = b.deferAckUntilCheckpoint;
        this.checkpointInterval = b.checkpointInterval == null ? Duration.ZERO : b.checkpointInterval;
        this.restoreFromLatestCheckpoint = b.restoreFromLatestCheckpoint;
        this.checkpointKeyPrefix = Objects.requireNonNull(b.checkpointKeyPrefix, "checkpointKeyPrefix");
        if (b.checkpointsToKeep < 0) {
            throw new IllegalArgumentException("checkpointsToKeep must be >= 0");
        }
        this.checkpointsToKeep = b.checkpointsToKeep;
        this.checkpointDrainTimeout = b.checkpointDrainTimeout == null ? Duration.ofSeconds(30) : b.checkpointDrainTimeout;
        this.mqOptions = b.mqOptions == null ? MqOptions.builder().build() : b.mqOptions;
        this.processingErrorResult = b.processingErrorResult == null ? MessageHandleResult.RETRY : b.processingErrorResult;
    }

    public String getJobName() {
        return jobName;
    }

    /**
     * Identifies a running job instance, used to construct a stable consumer name within a consumer group.
     */
    public String getJobInstanceId() {
        return jobInstanceId;
    }

    public String getStateKeyPrefix() {
        return stateKeyPrefix;
    }

    /**
     * Optional TTL applied to Redis state keys (hashes) created by the Redis runtime.
     *
     * <p>Note: TTL is applied per (job, topic, group, partition, operator, stateName) hash key.</p>
     */
    public Duration getStateTtl() {
        return stateTtl;
    }

    /**
     * When &gt; 0, the runtime samples Redis keyed state hash sizes (HLEN) every N state writes.
     *
     * <p>Used for operational visibility; 0 disables reporting.</p>
     */
    public int getStateSizeReportEveryNStateWrites() {
        return stateSizeReportEveryNStateWrites;
    }

    /**
     * Shard count for Redis keyed state hashes.
     *
     * <p>When &gt; 1, keyed state for a given (job, topic, group, partition, operator, stateName)
     * is split into multiple Redis hash keys by key hash, reducing single-hash hot spots and large hashes.</p>
     */
    public int getKeyedStateShardCount() {
        return keyedStateShardCount;
    }

    /**
     * Hot-key warning threshold (fields per keyed-state hash). 0 disables warning.
     */
    public long getKeyedStateHotKeyFieldsWarnThreshold() {
        return keyedStateHotKeyFieldsWarnThreshold;
    }

    /**
     * Minimum interval between hot-key warnings for the same state hash key.
     */
    public Duration getKeyedStateHotKeyWarnInterval() {
        return keyedStateHotKeyWarnInterval;
    }

    public boolean isStateSchemaEvolutionEnabled() {
        return stateSchemaEvolutionEnabled;
    }

    public StateSchemaMismatchPolicy getStateSchemaMismatchPolicy() {
        return stateSchemaMismatchPolicy;
    }

    /**
     * When enabled, and when the consumer group is missing, Redis runtime restores the group start offsets
     * from MQ commit frontier (acked message ids) instead of defaulting to "0-0".
     *
     * <p>This prevents accidental full reprocessing after a consumer group is deleted.</p>
     */
    public boolean isRestoreConsumerGroupFromCommitFrontier() {
        return restoreConsumerGroupFromCommitFrontier;
    }

    /**
     * Whether to enable sink-side deduplication (best-effort).
     *
     * <p>When enabled, Redis runtime records a marker per sink for each processed message (based on message id),
     * and skips invoking the sink again for the same message on retries/replays.</p>
     */
    public boolean isSinkDeduplicationEnabled() {
        return sinkDeduplicationEnabled;
    }

    /**
     * TTL for sink deduplication markers. Defaults to 7 days.
     */
    public Duration getSinkDeduplicationTtl() {
        return sinkDeduplicationTtl;
    }

    /**
     * Redis key prefix for sink deduplication sets. Actual keys include {@link #getJobName()} to avoid collisions.
     */
    public String getSinkDedupKeyPrefix() {
        return sinkDedupKeyPrefix;
    }

    /**
     * When enabled, runtime defers MQ ACK until a checkpoint is successfully stored (end-to-end best-effort).
     *
     * <p>Messages remain pending in the consumer group until a checkpoint completes.</p>
     */
    public boolean isDeferAckUntilCheckpoint() {
        return deferAckUntilCheckpoint;
    }

    /**
     * Periodic checkpoint interval. {@link Duration#ZERO} disables periodic checkpoints.
     *
     * <p>Checkpoints are stored in Redis and include best-effort snapshots of (source offsets, state).</p>
     */
    public Duration getCheckpointInterval() {
        return checkpointInterval;
    }

    /**
     * Whether to restore from the latest checkpoint (if present) before starting the job.
     */
    public boolean isRestoreFromLatestCheckpoint() {
        return restoreFromLatestCheckpoint;
    }

    /**
     * Redis key prefix for checkpoint objects. Actual keys include {@link #getJobName()} to avoid collisions.
     */
    public String getCheckpointKeyPrefix() {
        return checkpointKeyPrefix;
    }

    /**
     * How many checkpoints to keep. 0 disables cleanup.
     */
    public int getCheckpointsToKeep() {
        return checkpointsToKeep;
    }

    /**
     * Timeout for stop-the-world checkpoints: how long to wait for in-flight message handling to drain after pause().
     */
    public Duration getCheckpointDrainTimeout() {
        return checkpointDrainTimeout;
    }

    public MqOptions getMqOptions() {
        return mqOptions;
    }

    /**
     * What to do when pipeline execution throws an exception.
     *
     * <p>Typical choices:</p>
     * <ul>
     *   <li>{@link MessageHandleResult#RETRY}: retry with backoff (may end up in DLQ after max retries)</li>
     *   <li>{@link MessageHandleResult#DEAD_LETTER}: send to DLQ and ack (poison-pill fast path)</li>
     *   <li>{@link MessageHandleResult#FAIL}: send to DLQ and ack (same behavior as DEAD_LETTER in current MQ)</li>
     * </ul>
     */
    public MessageHandleResult getProcessingErrorResult() {
        return processingErrorResult;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String jobName = "redis-streaming-job";
        private String jobInstanceId = defaultInstanceId();
        private String stateKeyPrefix = "streaming:runtime";
        private Duration stateTtl = Duration.ZERO;
        private int stateSizeReportEveryNStateWrites = 0;
        private int keyedStateShardCount = 1;
        private long keyedStateHotKeyFieldsWarnThreshold = 0;
        private Duration keyedStateHotKeyWarnInterval = Duration.ofMinutes(1);
        private boolean stateSchemaEvolutionEnabled = true;
        private StateSchemaMismatchPolicy stateSchemaMismatchPolicy = StateSchemaMismatchPolicy.FAIL;
        private boolean restoreConsumerGroupFromCommitFrontier = true;
        private boolean sinkDeduplicationEnabled = false;
        private Duration sinkDeduplicationTtl = Duration.ofDays(7);
        private String sinkDedupKeyPrefix = "streaming:runtime:sinkDedup:";
        private boolean deferAckUntilCheckpoint = false;
        private Duration checkpointInterval = Duration.ZERO;
        private boolean restoreFromLatestCheckpoint = false;
        private String checkpointKeyPrefix = "streaming:runtime:checkpoint:";
        private int checkpointsToKeep = 5;
        private Duration checkpointDrainTimeout = Duration.ofSeconds(30);
        private MqOptions mqOptions;
        private MessageHandleResult processingErrorResult = MessageHandleResult.RETRY;

        public Builder jobName(String jobName) {
            if (jobName != null && !jobName.isBlank()) {
                this.jobName = jobName;
            }
            return this;
        }

        public Builder jobInstanceId(String jobInstanceId) {
            if (jobInstanceId != null && !jobInstanceId.isBlank()) {
                this.jobInstanceId = jobInstanceId;
            }
            return this;
        }

        public Builder stateKeyPrefix(String stateKeyPrefix) {
            if (stateKeyPrefix != null && !stateKeyPrefix.isBlank()) {
                this.stateKeyPrefix = stateKeyPrefix;
            }
            return this;
        }

        public Builder stateTtl(Duration stateTtl) {
            this.stateTtl = stateTtl == null ? Duration.ZERO : stateTtl;
            return this;
        }

        public Builder stateSizeReportEveryNStateWrites(int n) {
            this.stateSizeReportEveryNStateWrites = n;
            return this;
        }

        public Builder keyedStateShardCount(int shards) {
            this.keyedStateShardCount = shards;
            return this;
        }

        public Builder keyedStateHotKeyFieldsWarnThreshold(long fields) {
            this.keyedStateHotKeyFieldsWarnThreshold = fields;
            return this;
        }

        public Builder keyedStateHotKeyWarnInterval(Duration interval) {
            this.keyedStateHotKeyWarnInterval = interval;
            return this;
        }

        public Builder stateSchemaEvolutionEnabled(boolean enabled) {
            this.stateSchemaEvolutionEnabled = enabled;
            return this;
        }

        public Builder stateSchemaMismatchPolicy(StateSchemaMismatchPolicy policy) {
            this.stateSchemaMismatchPolicy = policy;
            return this;
        }

        public Builder restoreConsumerGroupFromCommitFrontier(boolean enabled) {
            this.restoreConsumerGroupFromCommitFrontier = enabled;
            return this;
        }

        public Builder sinkDeduplicationEnabled(boolean enabled) {
            this.sinkDeduplicationEnabled = enabled;
            return this;
        }

        public Builder sinkDeduplicationTtl(Duration ttl) {
            this.sinkDeduplicationTtl = ttl;
            return this;
        }

        public Builder sinkDedupKeyPrefix(String prefix) {
            if (prefix != null && !prefix.isBlank()) {
                this.sinkDedupKeyPrefix = prefix;
            }
            return this;
        }

        public Builder deferAckUntilCheckpoint(boolean enabled) {
            this.deferAckUntilCheckpoint = enabled;
            return this;
        }

        public Builder checkpointInterval(Duration interval) {
            this.checkpointInterval = interval == null ? Duration.ZERO : interval;
            return this;
        }

        public Builder restoreFromLatestCheckpoint(boolean enabled) {
            this.restoreFromLatestCheckpoint = enabled;
            return this;
        }

        public Builder checkpointKeyPrefix(String checkpointKeyPrefix) {
            if (checkpointKeyPrefix != null && !checkpointKeyPrefix.isBlank()) {
                this.checkpointKeyPrefix = checkpointKeyPrefix;
            }
            return this;
        }

        public Builder checkpointsToKeep(int checkpointsToKeep) {
            this.checkpointsToKeep = checkpointsToKeep;
            return this;
        }

        public Builder checkpointDrainTimeout(Duration timeout) {
            this.checkpointDrainTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            return this;
        }

        public Builder mqOptions(MqOptions mqOptions) {
            this.mqOptions = mqOptions;
            return this;
        }

        public Builder processingErrorResult(MessageHandleResult processingErrorResult) {
            this.processingErrorResult = processingErrorResult;
            return this;
        }

        public RedisRuntimeConfig build() {
            return new RedisRuntimeConfig(this);
        }

        private static String defaultInstanceId() {
            try {
                String host = SystemUtils.getLocalHostname();
                if (host != null && !host.isBlank()) {
                    return host;
                }
            } catch (Exception ignore) {
            }
            return "local";
        }
    }
}
