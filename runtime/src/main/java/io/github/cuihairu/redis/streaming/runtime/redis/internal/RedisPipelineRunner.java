package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RedissonClient;
import org.redisson.api.RSetCache;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;

import static io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID;

/**
 * Executes a frozen {@link RedisPipeline} using MQ callbacks.
 *
 * <p>Thread-safety: the runner is designed to be invoked concurrently by multiple consumer threads.</p>
 */
public final class RedisPipelineRunner<T> implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisPipelineRunner.class);

    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String consumerGroup;
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks;
    /** Aligned with {@link #sinks}; null for sinks that are not two-phase-commit sinks. */
    private final List<TwoPhaseCommitCoordinator> twoPhaseCoordinators;

    private final ScheduledExecutorService timerExecutor;
    private final boolean closeTimerExecutor;
    private final AtomicLong maxEventTimeMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong watermarkMs = new AtomicLong(Long.MIN_VALUE);
    /** RT-M3: idle watermark flush period in ms; 0 = disabled (record-driven only). */
    private final long watermarkIdleTimeoutMs;
    private final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
    /** Set by {@link Context#markIdle()}; the next idle sweep flushes regardless of record silence. */
    private final AtomicBoolean idleNow = new AtomicBoolean(false);
    /** Set when the idle flush raised the watermark to MAX_VALUE; the next record starts a fresh epoch. */
    private final AtomicBoolean idleFlushed = new AtomicBoolean(false);
    /** RT-M3: hooks run after each idle flush (windowed operators drain their due sets). */
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> idleFlushHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Object eventTimerLock = new Object();
    private final PriorityQueue<EventTimer> eventTimers = new PriorityQueue<>(
            (a, b) -> {
                int c = Long.compare(a.timestampMs, b.timestampMs);
                if (c != 0) return c;
                return Long.compare(a.seq, b.seq);
            }
    );
    private long timerSeq = 0L;
    private final AtomicLong lastEventTimeTimerOverflowWarnAtMs = new AtomicLong(0L);
    private final Object sinkLifecycleLock = new Object();
    private boolean sinksOpened = false;
    private boolean sinksClosed = false;

    public RedisPipelineRunner(RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String topic,
                              String consumerGroup,
                              List<RedisOperatorNode> operators,
                              List<StreamSink<Object>> sinks) {
        this(config, redissonClient, objectMapper, topic, consumerGroup, operators, sinks, null, true);
    }

    public RedisPipelineRunner(RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String topic,
                              String consumerGroup,
                              List<RedisOperatorNode> operators,
                              List<StreamSink<Object>> sinks,
                              ScheduledExecutorService timerExecutor,
                              boolean closeTimerExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
        this.twoPhaseCoordinators = buildTwoPhaseCoordinators(this.sinks);
        if (timerExecutor != null) {
            this.timerExecutor = timerExecutor;
            this.closeTimerExecutor = closeTimerExecutor;
        } else {
            ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
            ex.setRemoveOnCancelPolicy(true);
            this.timerExecutor = ex;
            this.closeTimerExecutor = true;
        }
        long idleTimeout = 0L;
        try {
            Duration d = config.getWatermarkIdleTimeout();
            if (d != null && !d.isZero() && !d.isNegative()) {
                idleTimeout = d.toMillis();
            }
        } catch (Exception ignore) {
        }
        this.watermarkIdleTimeoutMs = idleTimeout;
        if (watermarkIdleTimeoutMs > 0) {
            // sweep cadence: responsive for short timeouts, bounded at 1s for long ones
            // (the sweep body itself is two atomic reads and only flushes on idleness).
            // First sweep runs one period in so an early markIdle() flushes promptly.
            long period = Math.min(1000L, Math.max(1L, watermarkIdleTimeoutMs / 10));
            this.timerExecutor.scheduleWithFixedDelay(this::idleSweep,
                    period, period, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * RT-M3: idle watermark flush. Without it the watermark is purely record-driven, so a
     * pipeline whose source went quiet never fires its due event-time timers or window
     * close-fires again until a new record happens to arrive. After {@code
     * watermarkIdleTimeout} of record silence (or a generator's {@code markIdle()}), the
     * watermark is raised to {@code Long.MAX_VALUE} and the due event-time timers drain;
     * windowed operators drain their remaining due sets through registered flush hooks
     * ({@link Context#addIdleFlushHook}). The next record starts a fresh watermark epoch
     * ({@link #updateWatermark}), so a live source resumes normal event-time semantics.
     */
    private void idleSweep() {
        try {
            if (watermarkIdleTimeoutMs <= 0) {
                return;
            }
            if (!idleNow.get() && System.currentTimeMillis() - lastActivityMs.get() < watermarkIdleTimeoutMs) {
                return;
            }
            // watermarkMs.set racing a concurrent updateWatermark can lose the flush (the
            // record re-raises a lower candidate); that merely defers the flush to the next
            // sweep — best-effort by design.
            if (watermarkMs.get() == Long.MAX_VALUE) {
                return;
            }
            watermarkMs.set(Long.MAX_VALUE);
            idleFlushed.set(true);
            fireDueEventTimers();
            // Windowed operators register hooks so an idle flush drains their Redis-side
            // due sets (the watermark alone cannot reach them — their close state lives in
            // sorted sets, not in the event-time timer queue). One failing hook must not
            // block the others; the failed drain re-queues its members for the next flush
            // or record.
            for (Runnable hook : idleFlushHooks) {
                try {
                    hook.run();
                } catch (Exception e) {
                    log.warn("Idle flush hook failed (jobName={}, topic={}, group={})",
                            config.getJobName(), topic, consumerGroup, e);
                }
            }
            try {
                RedisRuntimeMetrics.get().setWatermarkMs(config.getJobName(), topic, consumerGroup, watermarkMs.get());
            } catch (Exception ignore) {
            }
        } catch (Exception e) {
            log.warn("Idle watermark flush failed (jobName={}, topic={}, group={})",
                    config.getJobName(), topic, consumerGroup, e);
        }
    }

    public boolean handle(Message message) throws Exception {
        if (message == null) {
            return true;
        }
        ensureSinksOpen();
        long now = System.currentTimeMillis();
        lastActivityMs.set(now);
        idleNow.set(false);
        long eventTime = extractEventTimeMs(message, now);
        updateWatermark(eventTime);
        Context ctx = new Context(message, now, eventTime);

        emitFrom(0, message, ctx);
        fireDueEventTimers();
        return true;
    }

    private long extractEventTimeMs(Message message, long fallback) {
        Instant ts = message.getTimestamp();
        return ts == null ? fallback : ts.toEpochMilli();
    }

    private void updateWatermark(long eventTimeMs) {
        if (idleFlushed.compareAndSet(true, false)) {
            // RT-M3: first record after an idle flush. The flush pinned the watermark at
            // MAX_VALUE — max() would keep it there forever — so this record starts a fresh
            // watermark epoch from its own event time.
            maxEventTimeMs.set(eventTimeMs);
            watermarkMs.set(candidateFor(eventTimeMs));
        } else {
            maxEventTimeMs.updateAndGet(prev -> Math.max(prev, eventTimeMs));
            watermarkMs.updateAndGet(prev -> Math.max(prev, candidateFor(maxEventTimeMs.get())));
        }
        try {
            RedisRuntimeMetrics.get().setWatermarkMs(config.getJobName(), topic, consumerGroup, watermarkMs.get());
        } catch (Exception ignore) {
        }
    }

    private long candidateFor(long maxEventTimeMs) {
        long outOfOrdernessMs = 0L;
        try {
            if (config.getWatermarkOutOfOrderness() != null) {
                outOfOrdernessMs = Math.max(0L, config.getWatermarkOutOfOrderness().toMillis());
            }
        } catch (Exception ignore) {
        }
        long candidate = maxEventTimeMs;
        if (outOfOrdernessMs > 0 && candidate != Long.MIN_VALUE) {
            candidate = candidate - outOfOrdernessMs;
        }
        return candidate;
    }

    private void emitFrom(int index, Object value, Context ctx) throws Exception {
        if (index >= operators.size()) {
            for (int i = 0; i < sinks.size(); i++) {
                StreamSink<Object> sink = sinks.get(i);
                TwoPhaseCommitCoordinator coordinator = twoPhaseCoordinators.get(i);
                if (!config.isSinkDeduplicationEnabled()) {
                    if (coordinator != null) {
                        coordinator.invoke(value);
                    } else {
                        sink.invoke(value);
                    }
                    continue;
                }
                if (!shouldInvokeSink(i, ctx)) {
                    continue;
                }
                if (coordinator != null) {
                    coordinator.invoke(value);
                } else {
                    sink.invoke(value);
                }
                markSinkInvoked(i, ctx);
            }
            return;
        }

        RedisOperatorNode op = operators.get(index);
        op.process(value, ctx, out -> emitFrom(index + 1, out, ctx));
    }

    private void fireDueEventTimers() throws Exception {
        long watermark = watermarkMs.get();
        while (true) {
            EventTimer next;
            synchronized (eventTimerLock) {
                next = eventTimers.peek();
                if (next == null || next.timestampMs > watermark) {
                    try {
                        RedisRuntimeMetrics.get().setEventTimeTimerQueueSize(config.getJobName(), topic, consumerGroup, eventTimers.size());
                    } catch (Exception ignore) {
                    }
                    return;
                }
                eventTimers.poll();
            }
            try {
                next.callback.run();
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    private boolean shouldInvokeSink(int sinkIndex, Context ctx) {
        if (ctx == null || ctx.message == null) {
            return true;
        }
        int pid = ctx.partitionId;
        if (pid < 0) {
            return true;
        }
        String mid = stableMessageId(ctx.message);
        if (mid == null || mid.isBlank()) {
            return true;
        }
        try {
            String key = sinkDedupKey(sinkIndex, pid);
            RSetCache<String> set = redissonClient.getSetCache(key, StringCodec.INSTANCE);
            return !set.contains(mid);
        } catch (Exception ignore) {
            return true;
        }
    }

    private void markSinkInvoked(int sinkIndex, Context ctx) {
        if (ctx == null || ctx.message == null) {
            return;
        }
        int pid = ctx.partitionId;
        if (pid < 0) {
            return;
        }
        String mid = stableMessageId(ctx.message);
        if (mid == null || mid.isBlank()) {
            return;
        }
        try {
            String key = sinkDedupKey(sinkIndex, pid);
            RSetCache<String> set = redissonClient.getSetCache(key, StringCodec.INSTANCE);
            Duration ttl = config.getSinkDeduplicationTtl();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                set.add(mid);
            } else {
                set.add(mid, ttl.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignore) {
        }
    }

    private String stableMessageId(Message message) {
        if (message == null) {
            return null;
        }
        try {
            if (message.getHeaders() != null) {
                String orig = message.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID);
                if (orig != null && !orig.isBlank()) {
                    return orig;
                }
            }
        } catch (Exception ignore) {
        }
        try {
            return message.getId();
        } catch (Exception ignore) {
            return null;
        }
    }

    private String sinkDedupKey(int sinkIndex, int partitionId) {
        return config.getSinkDedupKeyPrefix()
                + config.getJobName()
                + ":topic:" + topic
                + ":cg:" + consumerGroup
                + ":sink:" + sinkIndex
                + ":p:" + partitionId;
    }

    private void ensureSinksOpen() throws Exception {
        synchronized (sinkLifecycleLock) {
            if (sinksOpened || sinksClosed) {
                return;
            }
            for (StreamSink<Object> sink : sinks) {
                sink.open();
            }
            sinksOpened = true;
        }
    }

    @Override
    public void close() {
        if (closeTimerExecutor) {
            timerExecutor.shutdownNow();
        }
        synchronized (sinkLifecycleLock) {
            if (!sinksOpened || sinksClosed) {
                return;
            }
            for (StreamSink<Object> sink : sinks) {
                try {
                    sink.close();
                } catch (Exception e) {
                    log.warn("Failed to close sink for jobName={}, topic={}, group={}",
                            config.getJobName(), topic, consumerGroup, e);
                }
            }
            sinksClosed = true;
        }
    }

    public void onCheckpointStart(long checkpointId) throws Exception {
        for (StreamSink<Object> sink : sinks) {
            if (sink instanceof CheckpointAwareSink<?> s) {
                @SuppressWarnings("unchecked")
                CheckpointAwareSink<Object> cs = (CheckpointAwareSink<Object>) s;
                cs.onCheckpointStart(checkpointId);
            }
        }
    }

    public void onCheckpointComplete(long checkpointId) throws Exception {
        for (StreamSink<Object> sink : sinks) {
            if (sink instanceof CheckpointAwareSink<?> s) {
                @SuppressWarnings("unchecked")
                CheckpointAwareSink<Object> cs = (CheckpointAwareSink<Object>) s;
                cs.onCheckpointComplete(checkpointId);
            }
        }
    }

    public void onCheckpointAbort(long checkpointId, Throwable cause) {
        for (StreamSink<Object> sink : sinks) {
            if (sink instanceof CheckpointAwareSink<?> s) {
                @SuppressWarnings("unchecked")
                CheckpointAwareSink<Object> cs = (CheckpointAwareSink<Object>) s;
                try {
                    cs.onCheckpointAbort(checkpointId, cause);
                } catch (Exception ignore) {
                }
            }
        }
    }

    public void onCheckpointRestore(long checkpointId) throws Exception {
        for (StreamSink<Object> sink : sinks) {
            if (sink instanceof CheckpointAwareSink<?> s) {
                @SuppressWarnings("unchecked")
                CheckpointAwareSink<Object> cs = (CheckpointAwareSink<Object>) s;
                cs.onCheckpointRestore(checkpointId);
            }
        }
    }

    /**
     * Builds the per-sink two-phase-commit coordinators: one per {@link TwoPhaseCommitSink},
     * null entries for plain sinks, index-aligned with {@code sinks}.
     */
    private static List<TwoPhaseCommitCoordinator> buildTwoPhaseCoordinators(List<StreamSink<Object>> sinks) {
        List<TwoPhaseCommitCoordinator> coordinators = new java.util.ArrayList<>(sinks.size());
        for (StreamSink<Object> sink : sinks) {
            coordinators.add(sink instanceof TwoPhaseCommitSink<?, ?> tps
                    ? new TwoPhaseCommitCoordinator(tps)
                    : null);
        }
        // List.copyOf would reject the null entries; the list is built once and never mutated
        return java.util.Collections.unmodifiableList(coordinators);
    }

    /**
     * Two-phase-commit phase 1: pre-commits every two-phase-commit sink's open transaction and
     * returns the encoded transaction handles keyed by sink index. The caller must store these
     * handles into the checkpoint snapshot <em>before</em> calling
     * {@link #commitTwoPhaseCommits()} so recovery can compensate if the process dies in
     * between.
     */
    public Map<Integer, String> prepareTwoPhaseCommits() throws Exception {
        Map<Integer, String> handles = new LinkedHashMap<>();
        for (int i = 0; i < twoPhaseCoordinators.size(); i++) {
            TwoPhaseCommitCoordinator coordinator = twoPhaseCoordinators.get(i);
            if (coordinator != null) {
                handles.put(i, coordinator.prepareCommit());
            }
        }
        return handles;
    }

    /**
     * Two-phase-commit phase 2: finalizes all open two-phase-commit transactions. Called by
     * the environment after the checkpoint containing {@link #prepareTwoPhaseCommits()}'s
     * handles was stored successfully.
     */
    public void commitTwoPhaseCommits() throws Exception {
        for (TwoPhaseCommitCoordinator coordinator : twoPhaseCoordinators) {
            if (coordinator != null) {
                coordinator.commit();
            }
        }
    }

    /**
     * Discards all open two-phase-commit transactions. Called by the environment when the
     * checkpoint failed after the pre-commit phase.
     */
    public void abortTwoPhaseCommits() throws Exception {
        for (TwoPhaseCommitCoordinator coordinator : twoPhaseCoordinators) {
            if (coordinator != null) {
                coordinator.abort();
            }
        }
    }

    /**
     * True when at least one sink of this pipeline is a {@link TwoPhaseCommitSink}.
     */
    public boolean hasTwoPhaseCommitSinks() {
        return twoPhaseCoordinators.stream().anyMatch(Objects::nonNull);
    }

    /**
     * Recovery compensation: commits the transaction whose handle was restored from a stored
     * checkpoint (crash between the checkpoint store and the commit phase). Idempotent by
     * the {@link TwoPhaseCommitSink} contract.
     */
    public void recoverTwoPhaseCommit(int sinkIndex, String encodedTxn) throws Exception {
        TwoPhaseCommitCoordinator coordinator = twoPhaseCoordinators.get(sinkIndex);
        if (coordinator == null) {
            throw new IllegalStateException(
                    "sink " + sinkIndex + " of " + topic + "/" + consumerGroup + " is not a TwoPhaseCommitSink");
        }
        coordinator.recoverAndCommit(encodedTxn);
    }

    /**
     * Recovery compensation: discards the transaction whose handle was restored from a
     * rolled-back checkpoint.
     */
    public void recoverTwoPhaseAbort(int sinkIndex, String encodedTxn) throws Exception {
        TwoPhaseCommitCoordinator coordinator = twoPhaseCoordinators.get(sinkIndex);
        if (coordinator == null) {
            throw new IllegalStateException(
                    "sink " + sinkIndex + " of " + topic + "/" + consumerGroup + " is not a TwoPhaseCommitSink");
        }
        coordinator.recoverAndAbort(encodedTxn);
    }

    public final class Context {
        private final Message message;
        private final long eventTimeMs;
        private final int partitionId;

        private Context(Message message, long processingTimeMs, long eventTimeMs) {
            this.message = message;
            this.eventTimeMs = eventTimeMs;
            this.partitionId = extractPartitionId(message);
        }

        public Message message() {
            return message;
        }

        public int currentPartitionId() {
            return partitionId;
        }

        public long currentProcessingTime() {
            return System.currentTimeMillis();
        }

        public long currentWatermark() {
            return watermarkMs.get();
        }

        public long currentEventTime() {
            return eventTimeMs;
        }

        /**
         * Monotonically raise the pipeline watermark (never lowers it). Used by user-supplied
         * {@link io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator}s attached
         * via {@code assignTimestampsAndWatermarks}.
         */
        public void raiseWatermark(long watermarkMs) {
            RedisPipelineRunner.this.watermarkMs.updateAndGet(prev -> Math.max(prev, watermarkMs));
            try {
                RedisRuntimeMetrics.get().setWatermarkMs(config.getJobName(), topic, consumerGroup, RedisPipelineRunner.this.watermarkMs.get());
            } catch (Exception ignore) {
            }
        }

        /**
         * RT-M3: declare the pipeline idle (a {@link io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator}
         * that has run dry). The next idle sweep flushes the watermark even without record
         * silence. Only takes effect when {@code watermarkIdleTimeout} is configured, since
         * that is what schedules the sweep.
         */
        public void markIdle() {
            if (watermarkIdleTimeoutMs > 0) {
                idleNow.set(true);
            }
        }

        /**
         * RT-M3: clear a previous {@link #markIdle()} and record fresh activity.
         */
        public void markActive() {
            idleNow.set(false);
            lastActivityMs.set(System.currentTimeMillis());
        }

        /**
         * RT-M3: registers a hook that runs after every idle watermark flush (while the
         * watermark is still {@code MAX_VALUE}). Windowed operators use this to drain
         * their Redis-side window due sets when the pipeline goes quiet; on the record
         * path the per-record drain stays the sole owner of the due set, so trigger
         * semantics (e.g. defer-once close triggers) are untouched.
         */
        public void addIdleFlushHook(Runnable hook) {
            idleFlushHooks.addIfAbsent(hook);
        }

        /**
         * RT-M3: whether idle watermark flush is configured at all. Flush-dependent
         * machinery (due-set drain hooks) only registers when it is.
         */
        public boolean idleFlushEnabled() {
            return watermarkIdleTimeoutMs > 0;
        }



        public RedissonClient redissonClient() {
            return redissonClient;
        }

        public ObjectMapper objectMapper() {
            return objectMapper;
        }

        public RedisRuntimeConfig runtimeConfig() {
            return config;
        }

        public void registerProcessingTimeTimer(long triggerTimeMs, Runnable callback) {
            long delay = Math.max(0, triggerTimeMs - System.currentTimeMillis());
            timerExecutor.schedule(() -> {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.warn("Processing-time timer callback failed", e);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        public void registerEventTimeTimer(long triggerTimeMs, Runnable callback) {
            synchronized (eventTimerLock) {
                int max = 0;
                try {
                    max = Math.max(0, config.getEventTimeTimerMaxSize());
                } catch (Exception ignore) {
                }
                if (max > 0 && eventTimers.size() >= max) {
                    long now = System.currentTimeMillis();
                    long prev = lastEventTimeTimerOverflowWarnAtMs.get();
                    if (prev <= 0 || now - prev >= 60_000L) {
                        lastEventTimeTimerOverflowWarnAtMs.set(now);
                        log.warn("Redis runtime event-time timer queue is full; dropping timer registration (jobName={}, topic={}, group={}, maxSize={})",
                                config.getJobName(), topic, consumerGroup, max);
                    }
                    return;
                }
                eventTimers.add(new EventTimer(timerSeq++, triggerTimeMs, callback));
                try {
                    RedisRuntimeMetrics.get().setEventTimeTimerQueueSize(config.getJobName(), topic, consumerGroup, eventTimers.size());
                } catch (Exception ignore) {
                }
            }
        }

        public void emitFrom(int index, Object value) throws Exception {
            RedisPipelineRunner.this.emitFrom(index, value, this);
        }
    }

    @FunctionalInterface
    public interface Emitter {
        void emit(Object value) throws Exception;
    }

    private record EventTimer(long seq, long timestampMs, Runnable callback) {
    }

    private static int extractPartitionId(Message message) {
        if (message == null) {
            return -1;
        }
        try {
            if (message.getHeaders() == null) {
                return -1;
            }
            String v = message.getHeaders().get(PARTITION_ID);
            if (v == null || v.isBlank()) {
                return -1;
            }
            return Integer.parseInt(v);
        } catch (Exception ignore) {
            return -1;
        }
    }
}
