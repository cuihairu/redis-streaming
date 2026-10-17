package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;
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
import java.util.concurrent.atomic.AtomicLongArray;

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
    /** The pipeline's MQ sources (index 0 is the historical single source). */
    private final List<String> topics;
    private final List<String> consumerGroups;
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks;
    /** Aligned with {@link #sinks}; null for sinks that are not two-phase-commit sinks. */
    private final List<TwoPhaseCommitCoordinator> twoPhaseCoordinators;

    private final ScheduledExecutorService timerExecutor;
    private final boolean closeTimerExecutor;
    private final boolean assignerOwnedEventTime;
    /**
     * Phase 3: per-source max event time. An entry still at {@code Long.MIN_VALUE} means the
     * source has not delivered a record in the current epoch, so it contributes
     * {@code Long.MIN_VALUE} to the aligned watermark and thereby blocks it — a live but silent
     * source must not let the other source's windows close early.
     */
    private final AtomicLongArray sourceMaxEventTimeMs;
    /** Phase 3: per-source watermark candidate (what the source currently owes). */
    private final AtomicLongArray sourceWatermarkMs;
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
        this(config, redissonClient, objectMapper, topic, consumerGroup, operators, sinks, timerExecutor, closeTimerExecutor, false);
    }

    /**
     * @param assignerOwnedEventTime {@code true} when the chain wires a {@code TimestampAssigner}
     *        via {@code assignTimestampsAndWatermarks(assigner, generator)}: the user generator then
     *        owns watermark advancement and the built-in message-timestamp heuristic must not push
     *        its own (possibly far-ahead) watermark.
     */
    public RedisPipelineRunner(RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              String topic,
                              String consumerGroup,
                              List<RedisOperatorNode> operators,
                              List<StreamSink<Object>> sinks,
                              ScheduledExecutorService timerExecutor,
                              boolean closeTimerExecutor,
                              boolean assignerOwnedEventTime) {
        this(config, redissonClient, objectMapper, List.of(topic), List.of(consumerGroup), operators, sinks,
                timerExecutor, closeTimerExecutor, assignerOwnedEventTime);
    }

    /**
     * Phase 3 dual-source constructor. One runner consumes every source of the pipeline; the
     * event-time watermark is the <em>minimum</em> over the per-source candidates (see
     * {@link #updateAlignedWatermark()}), so a window only closes once every source has advanced
     * past it.
     */
    public RedisPipelineRunner(RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              List<String> topics,
                              List<String> consumerGroups,
                              List<RedisOperatorNode> operators,
                              List<StreamSink<Object>> sinks,
                              ScheduledExecutorService timerExecutor,
                              boolean closeTimerExecutor,
                              boolean assignerOwnedEventTime) {
        Objects.requireNonNull(topics, "topics");
        Objects.requireNonNull(consumerGroups, "consumerGroups");
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("A pipeline runner needs at least one MQ source");
        }
        if (topics.size() != consumerGroups.size()) {
            throw new IllegalArgumentException("topics and consumerGroups must have the same size");
        }
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.topics = List.copyOf(topics);
        this.consumerGroups = List.copyOf(consumerGroups);
        this.operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
        this.sourceMaxEventTimeMs = unseeded(topics.size());
        this.sourceWatermarkMs = unseeded(topics.size());
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
        this.assignerOwnedEventTime = assignerOwnedEventTime;
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
                            config.getJobName(), primaryTopic(), primaryGroup(), e);
                }
            }
            try {
                RedisRuntimeMetrics.get().setWatermarkMs(config.getJobName(), primaryTopic(), primaryGroup(), watermarkMs.get());
            } catch (Exception ignore) {
            }
        } catch (Exception e) {
            log.warn("Idle watermark flush failed (jobName={}, topic={}, group={})",
                    config.getJobName(), primaryTopic(), primaryGroup(), e);
        }
    }

    public boolean handle(Message message) throws Exception {
        return handle(message, 0);
    }

    /**
     * Handle one record from the {@code sourceIndex}-th source. The source owns the record's
     * watermark contribution (aligned minimum) and its sink-dedup key.
     */
    public boolean handle(Message message, int sourceIndex) throws Exception {
        if (message == null) {
            return true;
        }
        int source = sourceIndex;
        if (source < 0 || source >= topics.size()) {
            source = 0;
        }
        ensureSinksOpen();
        long now = System.currentTimeMillis();
        lastActivityMs.set(now);
        idleNow.set(false);
        long eventTime = extractEventTimeMs(message, now);
        updateSourceWatermark(source, eventTime);
        Context ctx = new Context(message, now, eventTime, source);

        emitFrom(0, message, ctx);
        fireDueEventTimers();
        return true;
    }

    /** The index of the {@code message}'s topic among this pipeline's sources, or {@code -1} when it
     * reads none of ours (the caller decides the fallback). */
    public int sourceIndexOf(Message message) {
        String topic = message == null ? null : message.getTopic();
        for (int i = 0; i < topics.size(); i++) {
            if (topics.get(i).equals(topic)) {
                return i;
            }
        }
        return -1;
    }

    private String primaryTopic() {
        return topics.get(0);
    }

    private String primaryGroup() {
        return consumerGroups.get(0);
    }

    private long extractEventTimeMs(Message message, long fallback) {
        Instant ts = message.getTimestamp();
        return ts == null ? fallback : ts.toEpochMilli();
    }

    /**
     * Fold one record's event time into its source's watermark and re-publish the aligned
     * watermark (the minimum over the sources).
     */
    private void updateSourceWatermark(int sourceIndex, long eventTimeMs) {
        boolean freshEpoch = idleFlushed.compareAndSet(true, false);
        if (freshEpoch) {
            // RT-M3: first record after an idle flush. The flush pinned the watermark at
            // MAX_VALUE — max() would keep it there forever — so this record starts a fresh
            // watermark epoch. The flush voided every source's "no event <= w arrives again"
            // promise, so all sources restart unseeded; the aligned watermark is re-derived
            // (set, not max) and only rises again once each source has delivered a record.
            for (int i = 0; i < sourceMaxEventTimeMs.length(); i++) {
                sourceMaxEventTimeMs.set(i, Long.MIN_VALUE);
                sourceWatermarkMs.set(i, Long.MIN_VALUE);
            }
            seedSource(sourceIndex, eventTimeMs);
            publishAlignedWatermark(true);
            return;
        }
        if (!assignerOwnedEventTime) {
            // assigner-owned: the user generator raises watermarks via Context#raiseWatermark;
            // a message-timestamp heuristic here could run ahead of assigned event times and
            // close windows early.
            sourceMaxEventTimeMs.accumulateAndGet(sourceIndex, eventTimeMs, Math::max);
            sourceWatermarkMs.set(sourceIndex, candidateFor(sourceMaxEventTimeMs.get(sourceIndex)));
        }
        publishAlignedWatermark(false);
    }

    private void seedSource(int sourceIndex, long eventTimeMs) {
        sourceMaxEventTimeMs.set(sourceIndex, eventTimeMs);
        sourceWatermarkMs.set(sourceIndex, assignerOwnedEventTime ? Long.MIN_VALUE : candidateFor(eventTimeMs));
    }

    /**
     * A watermark array where no source has spoken yet: {@link AtomicLongArray} defaults to 0,
     * which would read as a real watermark of 0 and fire every timer at or below 0 before the
     * first record arrives — the historical behavior is {@code Long.MIN_VALUE} (no timer is due).
     */
    private static AtomicLongArray unseeded(int size) {
        AtomicLongArray array = new AtomicLongArray(size);
        for (int i = 0; i < size; i++) {
            array.set(i, Long.MIN_VALUE);
        }
        return array;
    }

    /**
     * The aligned watermark: the minimum over the per-source candidates. A source that has not
     * delivered a record in the current epoch still owes {@code MIN_VALUE}, so it holds the
     * whole pipeline back — the conservative choice for a join, where a window may only close
     * once every side has advanced past it. A source that goes permanently quiet is unblocked by
     * the RT-M3 idle flush, which raises the watermark to MAX_VALUE and fires everything due.
     */
    private long alignedWatermark() {
        long aligned = Long.MAX_VALUE;
        for (int i = 0; i < sourceWatermarkMs.length(); i++) {
            aligned = Math.min(aligned, sourceWatermarkMs.get(i));
        }
        // Every entry starts at MIN_VALUE, so a pipeline whose sources have not all spoken yet
        // yields MIN_VALUE and fires no event-time timer — which is the point: a join must not
        // close a window before the peer side has had a chance to contribute.
        return aligned;
    }

    private void publishAlignedWatermark(boolean rederive) {
        long aligned = alignedWatermark();
        if (rederive) {
            // Fresh epoch after an idle flush: the previous value (MAX_VALUE) is void, so the
            // watermark is re-derived rather than max-merged.
            watermarkMs.set(aligned);
        } else {
            watermarkMs.accumulateAndGet(aligned, Math::max);
        }
        try {
            RedisRuntimeMetrics.get().setWatermarkMs(config.getJobName(), primaryTopic(), primaryGroup(), watermarkMs.get());
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
        // Shared bounded-out-of-orderness semantics (watermark w = no event <= w arrives again):
        // the -1 used to be missing here, silently shifting the late-event boundary by 1ms.
        return BoundedOutOfOrdernessWatermarkGenerator.watermarkFor(maxEventTimeMs, outOfOrdernessMs);
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
                        RedisRuntimeMetrics.get().setEventTimeTimerQueueSize(config.getJobName(), primaryTopic(), primaryGroup(), eventTimers.size());
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
            String key = sinkDedupKey(sinkIndex, pid, ctx.sourceIndex);
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
            String key = sinkDedupKey(sinkIndex, pid, ctx.sourceIndex);
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

    private String sinkDedupKey(int sinkIndex, int partitionId, int sourceIndex) {
        return config.getSinkDedupKeyPrefix()
                + config.getJobName()
                + ":topic:" + topics.get(sourceIndex)
                + ":cg:" + consumerGroups.get(sourceIndex)
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
                            config.getJobName(), primaryTopic(), primaryGroup(), e);
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
                    "sink " + sinkIndex + " of " + primaryTopic() + "/" + primaryGroup() + " is not a TwoPhaseCommitSink");
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
                    "sink " + sinkIndex + " of " + primaryTopic() + "/" + primaryGroup() + " is not a TwoPhaseCommitSink");
        }
        coordinator.recoverAndAbort(encodedTxn);
    }

    public final class Context {
        private final Message message;
        private long eventTimeMs;
        private final int partitionId;
        /** Phase 3: the source this record arrived on (0 for the historical single source). */
        final int sourceIndex;

        private Context(Message message, long processingTimeMs, long eventTimeMs) {
            this(message, processingTimeMs, eventTimeMs, 0);
        }

        private Context(Message message, long processingTimeMs, long eventTimeMs, int sourceIndex) {
            this.message = message;
            this.eventTimeMs = eventTimeMs;
            this.partitionId = extractPartitionId(message);
            this.sourceIndex = sourceIndex;
        }

        public Message message() {
            return message;
        }

        public int currentPartitionId() {
            return partitionId;
        }

        /** Phase 3: the topic this record arrived on (its source). */
        public String currentTopic() {
            return topics.get(sourceIndex);
        }

        /** Phase 3: the consumer group this record arrived on (its source). */
        public String currentConsumerGroup() {
            return consumerGroups.get(sourceIndex);
        }

        /** Phase 3: the source index this record arrived on. */
        public int currentSourceIndex() {
            return sourceIndex;
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
         * Re-binds this record's event time mid-chain: the
         * {@code assignTimestampsAndWatermarks(assigner, generator)} operator writes the
         * extracted timestamp here so downstream operators reading
         * {@link #currentEventTime()} (window assignment, timers) see event time instead of
         * the raw Message timestamp. Per-record and single-threaded (one Context flows down
         * one operator chain), so a plain field write is safe.
         */
        public void overrideEventTimeMs(long eventTimeMs) {
            this.eventTimeMs = eventTimeMs;
        }

        /**
         * Monotonically raise this record's source watermark (never lowers it), then re-publish
         * the aligned minimum over the sources. Used by user-supplied
         * {@link io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator}s attached
         * via {@code assignTimestampsAndWatermarks}: in a multi-source pipeline a generator's
         * raise only counts for the source it rides, so the pipeline watermark still waits for
         * the slower source. Per-source state isolation is the generator holder's job — see
         * the per-source factory overload of {@code assignTimestampsAndWatermarks}.
         */
        public void raiseWatermark(long watermarkMs) {
            sourceWatermarkMs.accumulateAndGet(sourceIndex, watermarkMs, Math::max);
            publishAlignedWatermark(false);
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
                    // RT-M2: a dropped registration would strand its window/callback forever.
                    // Fail the record instead — the MQ layer routes it to retry/DLQ, which is
                    // visible and recoverable (drain or raise eventTimeTimerMaxSize).
                    throw new IllegalStateException(
                            "Redis runtime event-time timer queue is full (size=" + eventTimers.size()
                                    + ", maxSize=" + max + ", jobName=" + config.getJobName()
                                    + ", topic=" + primaryTopic() + ", group=" + primaryGroup()
                                    + "); failing the record instead of silently dropping the timer — "
                                    + "raise RedisRuntimeConfig.eventTimeTimerMaxSize or fix the timer leak");
                }
                eventTimers.add(new EventTimer(timerSeq++, triggerTimeMs, callback));
                try {
                    RedisRuntimeMetrics.get().setEventTimeTimerQueueSize(config.getJobName(), primaryTopic(), primaryGroup(), eventTimers.size());
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
