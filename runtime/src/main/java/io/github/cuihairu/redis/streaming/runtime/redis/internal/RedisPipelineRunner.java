package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
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
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    private final ScheduledExecutorService timerExecutor;
    private final boolean closeTimerExecutor;
    private final AtomicLong maxEventTimeMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong watermarkMs = new AtomicLong(Long.MIN_VALUE);
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
        if (timerExecutor != null) {
            this.timerExecutor = timerExecutor;
            this.closeTimerExecutor = closeTimerExecutor;
        } else {
            ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
            ex.setRemoveOnCancelPolicy(true);
            this.timerExecutor = ex;
            this.closeTimerExecutor = true;
        }
    }

    public boolean handle(Message message) throws Exception {
        if (message == null) {
            return true;
        }
        long now = System.currentTimeMillis();
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
        maxEventTimeMs.updateAndGet(prev -> Math.max(prev, eventTimeMs));
        long max = maxEventTimeMs.get();
        long outOfOrdernessMs = 0L;
        try {
            if (config.getWatermarkOutOfOrderness() != null) {
                outOfOrdernessMs = Math.max(0L, config.getWatermarkOutOfOrderness().toMillis());
            }
        } catch (Exception ignore) {
        }
        long candidate = max;
        if (outOfOrdernessMs > 0 && candidate != Long.MIN_VALUE) {
            candidate = candidate - outOfOrdernessMs;
        }
        final long wmCandidate = candidate;
        watermarkMs.updateAndGet(prev -> Math.max(prev, wmCandidate));
        try {
            RedisRuntimeMetrics.get().setWatermarkMs(config.getJobName(), topic, consumerGroup, watermarkMs.get());
        } catch (Exception ignore) {
        }
    }

    private void emitFrom(int index, Object value, Context ctx) throws Exception {
        if (index >= operators.size()) {
            for (int i = 0; i < sinks.size(); i++) {
                StreamSink<Object> sink = sinks.get(i);
                if (!config.isSinkDeduplicationEnabled()) {
                    sink.invoke(value);
                    continue;
                }
                if (!shouldInvokeSink(i, ctx)) {
                    continue;
                }
                sink.invoke(value);
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

    @Override
    public void close() {
        if (closeTimerExecutor) {
            timerExecutor.shutdownNow();
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
