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

    /**
     * Handling applied to writes hitting a keyed-state hash that was detected hot (field
     * count at/above {@code keyedStateHotKeyFieldsWarnThreshold}).
     *
     * <p>Detection is sampled (every {@code stateSizeReportEveryNStateWrites} writes) and
     * arms a per-key handling window of {@code keyedStateHotKeyWarnInterval}; while the
     * window is active, every write to that key goes through the policy:</p>
     *
     * <ul>
     *   <li>{@link #LOG_ONLY} — warn log + metric only (previous behavior).</li>
     *   <li>{@link #THROTTLE} — additionally sleep up to {@code keyedStateHotKeyThrottleMaxMs}
     *       before the write, using latency as backpressure against the hot key.</li>
     *   <li>{@link #FAIL_FAST} — additionally throw {@link KeyedStateHotKeyException}; the MQ
     *       consumer's retry/backoff machinery turns this into backpressure and, after the
     *       configured attempts, routes the record to the dead-letter queue.</li>
     * </ul>
     */
    public enum HotKeyPolicy {
        LOG_ONLY,
        THROTTLE,
        FAIL_FAST
    }

    private final String jobName;
    private final String jobInstanceId;
    private final String stateKeyPrefix;
    private final Duration stateTtl;
    private final int stateSizeReportEveryNStateWrites;
    private final int keyedStateShardCount;
    private final long keyedStateHotKeyFieldsWarnThreshold;
    private final Duration keyedStateHotKeyWarnInterval;
    private final HotKeyPolicy keyedStateHotKeyPolicy;
    private final long keyedStateHotKeyThrottleMaxMs;
    private final boolean stateSchemaEvolutionEnabled;
    private final StateSchemaMismatchPolicy stateSchemaMismatchPolicy;
    private final boolean restoreConsumerGroupFromCommitFrontier;
    private final boolean sinkDeduplicationEnabled;
    private final Duration sinkDeduplicationTtl;
    private final String sinkDedupKeyPrefix;
    private final boolean deferAckUntilCheckpoint;
    private final boolean ackDeferredMessagesOnCheckpoint;
    private final int pipelineParallelism;
    private final int timerThreads;
    private final int checkpointThreads;
    private final int eventTimeTimerMaxSize;
    private final Duration watermarkOutOfOrderness;
    private final Duration windowAllowedLateness;
    private final int windowMaxFiresPerRecord;
    private final boolean mdcEnabled;
    private final double mdcSampleRate;
    private final Duration checkpointInterval;
    private final boolean restoreFromLatestCheckpoint;
    private final String checkpointKeyPrefix;
    private final int checkpointsToKeep;
    private final Duration checkpointDrainTimeout;
    private final MqOptions mqOptions;
    private final MessageHandleResult processingErrorResult;
    private final boolean leaderElectionEnabled;
    private final Duration leaderLeaseTtl;
    private final Duration leaderRenewInterval;

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
        this.keyedStateHotKeyPolicy = b.keyedStateHotKeyPolicy == null ? HotKeyPolicy.LOG_ONLY : b.keyedStateHotKeyPolicy;
        if (b.keyedStateHotKeyThrottleMaxMs < 0) {
            throw new IllegalArgumentException("keyedStateHotKeyThrottleMaxMs must be >= 0");
        }
        this.keyedStateHotKeyThrottleMaxMs = b.keyedStateHotKeyThrottleMaxMs;
        this.stateSchemaEvolutionEnabled = b.stateSchemaEvolutionEnabled;
        this.stateSchemaMismatchPolicy = b.stateSchemaMismatchPolicy == null ? StateSchemaMismatchPolicy.FAIL : b.stateSchemaMismatchPolicy;
        this.restoreConsumerGroupFromCommitFrontier = b.restoreConsumerGroupFromCommitFrontier;
        this.sinkDeduplicationEnabled = b.sinkDeduplicationEnabled;
        this.sinkDeduplicationTtl = b.sinkDeduplicationTtl == null ? Duration.ofDays(7) : b.sinkDeduplicationTtl;
        this.sinkDedupKeyPrefix = Objects.requireNonNull(b.sinkDedupKeyPrefix, "sinkDedupKeyPrefix");
        this.deferAckUntilCheckpoint = b.deferAckUntilCheckpoint;
        this.ackDeferredMessagesOnCheckpoint = b.ackDeferredMessagesOnCheckpoint;
        if (b.pipelineParallelism <= 0) {
            throw new IllegalArgumentException("pipelineParallelism must be >= 1");
        }
        this.pipelineParallelism = b.pipelineParallelism;
        if (b.timerThreads <= 0) {
            throw new IllegalArgumentException("timerThreads must be >= 1");
        }
        this.timerThreads = b.timerThreads;
        if (b.checkpointThreads <= 0) {
            throw new IllegalArgumentException("checkpointThreads must be >= 1");
        }
        this.checkpointThreads = b.checkpointThreads;
        if (b.eventTimeTimerMaxSize < 0) {
            throw new IllegalArgumentException("eventTimeTimerMaxSize must be >= 0");
        }
        this.eventTimeTimerMaxSize = b.eventTimeTimerMaxSize;
        this.watermarkOutOfOrderness = b.watermarkOutOfOrderness == null ? Duration.ZERO : b.watermarkOutOfOrderness;
        if (this.watermarkOutOfOrderness.isNegative()) {
            throw new IllegalArgumentException("watermarkOutOfOrderness must be >= 0");
        }
        this.windowAllowedLateness = b.windowAllowedLateness == null ? Duration.ZERO : b.windowAllowedLateness;
        if (this.windowAllowedLateness.isNegative()) {
            throw new IllegalArgumentException("windowAllowedLateness must be >= 0");
        }
        if (b.windowMaxFiresPerRecord <= 0) {
            throw new IllegalArgumentException("windowMaxFiresPerRecord must be >= 1");
        }
        this.windowMaxFiresPerRecord = b.windowMaxFiresPerRecord;
        this.mdcEnabled = b.mdcEnabled;
        if (Double.isNaN(b.mdcSampleRate) || b.mdcSampleRate < 0.0d || b.mdcSampleRate > 1.0d) {
            throw new IllegalArgumentException("mdcSampleRate must be in [0, 1]");
        }
        this.mdcSampleRate = b.mdcSampleRate;
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
        this.leaderElectionEnabled = b.leaderElectionEnabled;
        this.leaderLeaseTtl = b.leaderLeaseTtl == null ? Duration.ofSeconds(30) : b.leaderLeaseTtl;
        if (this.leaderLeaseTtl.isZero() || this.leaderLeaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaderLeaseTtl must be > 0");
        }
        this.leaderRenewInterval = b.leaderRenewInterval == null ? Duration.ofSeconds(10) : b.leaderRenewInterval;
        if (this.leaderRenewInterval.isZero() || this.leaderRenewInterval.isNegative()) {
            throw new IllegalArgumentException("leaderRenewInterval must be > 0");
        }
        if (this.leaderRenewInterval.compareTo(this.leaderLeaseTtl) >= 0) {
            throw new IllegalArgumentException("leaderRenewInterval must be < leaderLeaseTtl");
        }
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
     * Minimum interval between hot-key warnings for the same state hash key. Also the length
     * of the handling window armed per key on a hot detection.
     */
    public Duration getKeyedStateHotKeyWarnInterval() {
        return keyedStateHotKeyWarnInterval;
    }

    /**
     * Handling applied to writes hitting a detected-hot keyed-state hash
     * (default {@link HotKeyPolicy#LOG_ONLY}).
     */
    public HotKeyPolicy getKeyedStateHotKeyPolicy() {
        return keyedStateHotKeyPolicy;
    }

    /**
     * Upper bound of the per-write sleep applied while {@link HotKeyPolicy#THROTTLE} handling
     * is active for a hot key.
     */
    public long getKeyedStateHotKeyThrottleMaxMs() {
        return keyedStateHotKeyThrottleMaxMs;
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
     * When {@link #isDeferAckUntilCheckpoint()} is enabled, whether the runtime should ACK deferred messages
     * after {@code onCheckpointComplete()}.
     *
     * <p>Disable this if a Redis-only sink performs its own atomic {@code XACK} (e.g. via Lua) on checkpoint completion.</p>
     */
    public boolean isAckDeferredMessagesOnCheckpoint() {
        return ackDeferredMessagesOnCheckpoint;
    }

    /**
     * Number of parallel consumer subtasks per pipeline within one process.
     *
     * <p>Redis runtime uses partition pinning (partitionId % parallelism) to deterministically split partitions
     * across subtasks in the same consumer group.</p>
     */
    public int getPipelineParallelism() {
        return pipelineParallelism;
    }

    /**
     * Shared timer thread pool size used by Redis runtime for processing-time timers.
     *
     * <p>Resource model: avoid creating one thread per pipeline runner.</p>
     */
    public int getTimerThreads() {
        return timerThreads;
    }

    /**
     * Thread count for periodic checkpoint scheduling/execution within one process.
     *
     * <p>Note: checkpoint execution is still serialized per job via an atomic guard.</p>
     */
    public int getCheckpointThreads() {
        return checkpointThreads;
    }

    /**
     * Max in-memory event-time timer queue size per pipeline runner. 0 means unlimited.
     *
     * <p>This protects the runtime from unbounded growth when user logic registers too many event-time timers.</p>
     */
    public int getEventTimeTimerMaxSize() {
        return eventTimeTimerMaxSize;
    }

    /**
     * Max out-of-orderness for event-time watermarks.
     *
     * <p>Watermark is computed as {@code maxObservedEventTime - watermarkOutOfOrderness}.</p>
     */
    public Duration getWatermarkOutOfOrderness() {
        return watermarkOutOfOrderness;
    }

    /**
     * Allowed lateness for window operators (best-effort).
     *
     * <p>Redis runtime delays final window firing until {@code windowEnd + windowAllowedLateness}.</p>
     */
    public Duration getWindowAllowedLateness() {
        return windowAllowedLateness;
    }

    /**
     * Max number of window firings processed per record (to bound per-record work).
     */
    public int getWindowMaxFiresPerRecord() {
        return windowMaxFiresPerRecord;
    }

    /**
     * Whether to install per-message MDC keys (job/topic/group/consumer/id/key/partition) around handler execution.
     */
    public boolean isMdcEnabled() {
        return mdcEnabled;
    }

    /**
     * Sampling rate for MDC installation (when {@link #isMdcEnabled()} is true). Range: [0, 1].
     */
    public double getMdcSampleRate() {
        return mdcSampleRate;
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

    /**
     * Whether multi-node coordination is enabled (leader election + fencing token).
     *
     * <p>Default false: every instance behaves as today. When enabled, instances compete
     * for a Redis-backed lease and only the leader runs coordination duties (periodic
     * checkpoint scheduling), preventing split-brain double-writes.</p>
     */
    public boolean isLeaderElectionEnabled() {
        return leaderElectionEnabled;
    }

    /**
     * Leadership lease TTL. The lease expires automatically if the leader dies or stalls,
     * allowing another instance to take over. Must be positive.
     */
    public Duration getLeaderLeaseTtl() {
        return leaderLeaseTtl;
    }

    /**
     * Interval at which the leader renews its lease. Must be positive and smaller than
     * {@link #getLeaderLeaseTtl()} so the lease cannot expire between renewals.
     */
    public Duration getLeaderRenewInterval() {
        return leaderRenewInterval;
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
        private HotKeyPolicy keyedStateHotKeyPolicy = HotKeyPolicy.LOG_ONLY;
        private long keyedStateHotKeyThrottleMaxMs = 200;
        private boolean stateSchemaEvolutionEnabled = true;
        private StateSchemaMismatchPolicy stateSchemaMismatchPolicy = StateSchemaMismatchPolicy.FAIL;
        private boolean restoreConsumerGroupFromCommitFrontier = true;
        private boolean sinkDeduplicationEnabled = false;
        private Duration sinkDeduplicationTtl = Duration.ofDays(7);
        private String sinkDedupKeyPrefix = "streaming:runtime:sinkDedup:";
        private boolean deferAckUntilCheckpoint = false;
        private boolean ackDeferredMessagesOnCheckpoint = true;
        private int pipelineParallelism = 1;
        private int timerThreads = 1;
        private int checkpointThreads = 1;
        private int eventTimeTimerMaxSize = 100_000;
        private Duration watermarkOutOfOrderness = Duration.ZERO;
        private Duration windowAllowedLateness = Duration.ZERO;
        private int windowMaxFiresPerRecord = 256;
        private boolean mdcEnabled = false;
        private double mdcSampleRate = 1.0d;
        private Duration checkpointInterval = Duration.ZERO;
        private boolean restoreFromLatestCheckpoint = false;
        private String checkpointKeyPrefix = "streaming:runtime:checkpoint:";
        private int checkpointsToKeep = 5;
        private Duration checkpointDrainTimeout = Duration.ofSeconds(30);
        private MqOptions mqOptions;
        private MessageHandleResult processingErrorResult = MessageHandleResult.RETRY;
        private boolean leaderElectionEnabled = false;
        private Duration leaderLeaseTtl = Duration.ofSeconds(30);
        private Duration leaderRenewInterval = Duration.ofSeconds(10);

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

        public Builder keyedStateHotKeyPolicy(HotKeyPolicy policy) {
            this.keyedStateHotKeyPolicy = policy;
            return this;
        }

        public Builder keyedStateHotKeyThrottleMaxMs(long maxMs) {
            this.keyedStateHotKeyThrottleMaxMs = maxMs;
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

        public Builder ackDeferredMessagesOnCheckpoint(boolean enabled) {
            this.ackDeferredMessagesOnCheckpoint = enabled;
            return this;
        }

        public Builder pipelineParallelism(int parallelism) {
            this.pipelineParallelism = parallelism;
            return this;
        }

        public Builder timerThreads(int threads) {
            this.timerThreads = threads;
            return this;
        }

        public Builder checkpointThreads(int threads) {
            this.checkpointThreads = threads;
            return this;
        }

        public Builder eventTimeTimerMaxSize(int maxSize) {
            this.eventTimeTimerMaxSize = maxSize;
            return this;
        }

        public Builder watermarkOutOfOrderness(Duration outOfOrderness) {
            this.watermarkOutOfOrderness = outOfOrderness == null ? Duration.ZERO : outOfOrderness;
            return this;
        }

        public Builder windowAllowedLateness(Duration allowedLateness) {
            this.windowAllowedLateness = allowedLateness == null ? Duration.ZERO : allowedLateness;
            return this;
        }

        public Builder windowMaxFiresPerRecord(int max) {
            this.windowMaxFiresPerRecord = max;
            return this;
        }

        public Builder mdcEnabled(boolean enabled) {
            this.mdcEnabled = enabled;
            return this;
        }

        public Builder mdcSampleRate(double sampleRate) {
            this.mdcSampleRate = sampleRate;
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
            // RT-M1: a ZERO/negative timeout used to disable the drain deadline entirely, turning
            // the checkpoint drain loop into an infinite loop with all consumers paused. Only a
            // strictly positive duration is meaningful; anything else falls back to the default.
            this.checkpointDrainTimeout =
                    (timeout == null || timeout.isZero() || timeout.isNegative())
                            ? Duration.ofSeconds(30)
                            : timeout;
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

        public Builder leaderElectionEnabled(boolean enabled) {
            this.leaderElectionEnabled = enabled;
            return this;
        }

        public Builder leaderLeaseTtl(Duration ttl) {
            this.leaderLeaseTtl = ttl;
            return this;
        }

        public Builder leaderRenewInterval(Duration interval) {
            this.leaderRenewInterval = interval;
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
