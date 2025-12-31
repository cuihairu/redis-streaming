package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final List<RedisOperatorNode> operators;
    private final List<StreamSink<Object>> sinks;

    private final ScheduledExecutorService timerExecutor;
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

    public RedisPipelineRunner(RedisRuntimeConfig config,
                              RedissonClient redissonClient,
                              ObjectMapper objectMapper,
                              List<RedisOperatorNode> operators,
                              List<StreamSink<Object>> sinks) {
        this.config = Objects.requireNonNull(config, "config");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.operators = List.copyOf(Objects.requireNonNull(operators, "operators"));
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
        ex.setRemoveOnCancelPolicy(true);
        this.timerExecutor = ex;
    }

    public boolean handle(Message message) throws Exception {
        if (message == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        long eventTime = extractEventTimeMs(message, now);
        watermarkMs.updateAndGet(prev -> Math.max(prev, eventTime));
        Context ctx = new Context(message, now, eventTime);

        emitFrom(0, message, ctx);
        fireDueEventTimers();
        return true;
    }

    private long extractEventTimeMs(Message message, long fallback) {
        Instant ts = message.getTimestamp();
        return ts == null ? fallback : ts.toEpochMilli();
    }

    private void emitFrom(int index, Object value, Context ctx) throws Exception {
        if (index >= operators.size()) {
            for (StreamSink<Object> sink : sinks) {
                sink.invoke(value);
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

    @Override
    public void close() {
        timerExecutor.shutdownNow();
    }

    public final class Context {
        private final Message message;
        private final long eventTimeMs;

        private Context(Message message, long processingTimeMs, long eventTimeMs) {
            this.message = message;
            this.eventTimeMs = eventTimeMs;
        }

        public Message message() {
            return message;
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
                eventTimers.add(new EventTimer(timerSeq++, triggerTimeMs, callback));
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
}
