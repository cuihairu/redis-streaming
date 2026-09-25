package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.SelectiveFailingMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@link RedisPipelineRunner} and its {@code Context}: watermark computation
 * (null / throwing / zero / MIN_VALUE out-of-orderness), event timer queue metric failures,
 * sink-dedup bypasses for missing message ids, dedup TTL arms, {@code stableMessageId} shapes
 * (including vanishing headers), the post-close {@code ensureSinksOpen} guard, {@code raiseWatermark}
 * metric failure and event-time timer overflow + warn throttling.
 */
class RedisPipelineRunnerGapClosureTest {

    private RedissonClient redisson;
    private RSetCache<String> setCache;
    private RedisRuntimeMetricsCollector previousCollector;
    private RedisRuntimeConfig realConfig;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        setCache = mock(RSetCache.class);
        when(redisson.getSetCache(anyString(), any(Codec.class))).thenReturn((RSetCache) setCache);
        when(setCache.contains(anyString())).thenReturn(false);
        previousCollector = RedisRuntimeMetrics.get();
        realConfig = RedisRuntimeConfig.builder()
                .jobName("runner-gap")
                .stateKeyPrefix("it-runner-gap")
                .build();
    }

    @AfterEach
    void tearDown() {
        RedisRuntimeMetrics.setCollector(previousCollector);
    }

    private RedisRuntimeConfig cfg(java.util.function.Consumer<RedisRuntimeConfig.Builder> tweaks) {
        RedisRuntimeConfig.Builder b = RedisRuntimeConfig.builder()
                .jobName("runner-gap")
                .stateKeyPrefix("it-runner-gap");
        tweaks.accept(b);
        return b.build();
    }

    private static Message msg(long eventTimeMs, String id) {
        Message m = new Message();
        m.setId(id);
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setHeaders(new ConcurrentHashMap<>(Map.of(MqHeaders.PARTITION_ID, "0")));
        return m;
    }

    private static RedisPipelineRunner<Object> runner(RedisRuntimeConfig config, RedissonClient redis,
                                                      StreamSink<Object> sink, RedisOperatorNode... ops) {
        return new RedisPipelineRunner<>(config, redis, new ObjectMapper(), "t", "g",
                List.of(ops), List.of(sink));
    }

    @Test
    void updateWatermarkHandlesNullThrowingAndZeroOutOfOrderness() throws Exception {
        AtomicReference<Long> wm = new AtomicReference<>();
        RedisOperatorNode observe = (value, ctx, emit) -> wm.set(ctx.currentWatermark());

        RedisRuntimeConfig nullOod = mock(RedisRuntimeConfig.class, delegatesTo(realConfig));
        when(nullOod.getWatermarkOutOfOrderness()).thenReturn(null);
        RedisPipelineRunner<Object> r1 = runner(nullOod, redisson, v -> {
        }, observe);
        r1.handle(msg(100_000L, "1-1"));
        assertEquals(100_000L, wm.get());
        r1.close();

        RedisRuntimeConfig throwingOod = mock(RedisRuntimeConfig.class, delegatesTo(realConfig));
        when(throwingOod.getWatermarkOutOfOrderness()).thenThrow(new IllegalStateException("ood down"));
        RedisPipelineRunner<Object> r2 = runner(throwingOod, redisson, v -> {
        }, observe);
        r2.handle(msg(100_000L, "1-2"));
        assertEquals(100_000L, wm.get(), "throwing config falls back to zero out-of-orderness");
        r2.close();

        RedisPipelineRunner<Object> r3 = runner(cfg(b -> b.watermarkOutOfOrderness(Duration.ZERO)), redisson, v -> {
        }, observe);
        r3.handle(msg(100_000L, "1-3"));
        assertEquals(100_000L, wm.get());
        r3.close();
    }

    @Test
    void updateWatermarkSubtractsOutOfOrdernessButGuardsMinValue() throws Exception {
        AtomicReference<Long> wm = new AtomicReference<>();
        RedisOperatorNode observe = (value, ctx, emit) -> wm.set(ctx.currentWatermark());
        RedisRuntimeConfig cfg = cfg(b -> b.watermarkOutOfOrderness(Duration.ofSeconds(5)));

        RedisPipelineRunner<Object> r = runner(cfg, redisson, v -> {
        }, observe);
        r.handle(msg(100_000L, "1-1"));
        assertEquals(95_000L, wm.get(), "watermark lags event time by out-of-orderness");
        r.close();

        RedisPipelineRunner<Object> min = runner(cfg, redisson, v -> {
        }, observe);
        min.handle(msg(Long.MIN_VALUE, "1-2"));
        assertEquals(Long.MIN_VALUE, wm.get(), "MIN_VALUE event time must not underflow");
        min.close();
    }

    @Test
    void watermarkAndTimerQueueMetricsFailuresAreSwallowed() {
        RedisRuntimeMetrics.setCollector(new SelectiveFailingMetricsCollector()
                .failOn("setWatermarkMs", "setEventTimeTimerQueueSize"));
        RedisPipelineRunner<Object> r = runner(realConfig, redisson, v -> {
        });
        Message m = msg(1_000L, "1-1");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> r.handle(m));
        r.close();
    }

    @Test
    void raiseWatermarkSwallowsMetricFailureAndIsMonotonic() throws Exception {
        AtomicReference<Long> seen = new AtomicReference<>();
        RedisOperatorNode raise = (value, ctx, emit) -> {
            ctx.raiseWatermark(5_000L);
            ctx.raiseWatermark(4_000L);
            emit.emit(value);
        };
        RedisOperatorNode observe = (value, ctx, emit) -> seen.set(ctx.currentWatermark());
        RedisRuntimeMetrics.setCollector(new SelectiveFailingMetricsCollector().failOn("setWatermarkMs"));
        RedisPipelineRunner<Object> r = runner(realConfig, redisson, v -> {
        }, raise, observe);
        try {
            r.handle(msg(1_000L, "1-1"));
            assertEquals(5_000L, seen.get(), "raiseWatermark never lowers the watermark");
        } finally {
            r.close();
        }
    }

    @Test
    void sinkDedupBypassesBlankAndMissingMessageIds() throws Exception {
        RedisRuntimeConfig cfg = cfg(b -> b.sinkDeduplicationEnabled(true));
        List<Object> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        RedisPipelineRunner<Object> r = runner(cfg, redisson, out::add);

        Message nullId = msg(1_000L, null);
        r.handle(nullId);
        Message blankId = msg(1_000L, "   ");
        r.handle(blankId);
        assertEquals(List.of(nullId, blankId), out, "missing ids must not suppress sinks");
        verify(setCache, never()).contains(anyString());
        r.close();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void markSinkInvokedCoversAllDedupTtlArms() throws Exception {
        for (Duration ttl : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-3), Duration.ofMinutes(2)}) {
            RSetCache<String> cache = mock(RSetCache.class);
            when(redisson.getSetCache(anyString(), any(Codec.class))).thenReturn((RSetCache) cache);
            RedisRuntimeConfig base = cfg(b -> b.sinkDeduplicationEnabled(true));
            RedisRuntimeConfig cfg = mock(RedisRuntimeConfig.class, delegatesTo(base));
            when(cfg.getSinkDeduplicationTtl()).thenReturn(ttl);
            RedisPipelineRunner<Object> r = runner(cfg, redisson, v -> {
            });
            r.handle(msg(1_000L, "1-1"));
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                verify(cache).add("1-1");
            } else {
                verify(cache).add("1-1", ttl.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            r.close();
        }
    }

    @Test
    void stableMessageIdHandlesNullVanishingHeadersAndOrigShapes() throws Exception {
        Method stable = RedisPipelineRunner.class.getDeclaredMethod("stableMessageId", Message.class);
        stable.setAccessible(true);
        RedisPipelineRunner<Object> r = runner(cfg(b -> b.sinkDeduplicationEnabled(true)), redisson, v -> {
        });
        assertNull(stable.invoke(r, (Object) null));

        Message vanishing = new Message() {
            private int reads;

            @Override
            public java.util.Map<String, String> getHeaders() {
                reads++;
                return reads <= 2 ? super.getHeaders() : null;
            }
        };
        vanishing.setId("9-1");
        vanishing.setHeaders(new ConcurrentHashMap<>(Map.of(MqHeaders.PARTITION_ID, "0")));
        r.handle(vanishing);
        verify(setCache).add(eq("9-1"), anyLong(), any(java.util.concurrent.TimeUnit.class));

        Message origBlank = msg(1_000L, "9-2");
        origBlank.getHeaders().put(MqHeaders.ORIGINAL_MESSAGE_ID, "   ");
        r.handle(origBlank);
        verify(setCache).add(eq("9-2"), anyLong(), any(java.util.concurrent.TimeUnit.class));

        Message origSet = msg(1_000L, "9-3");
        origSet.getHeaders().put(MqHeaders.ORIGINAL_MESSAGE_ID, "orig-9");
        r.handle(origSet);
        verify(setCache).add(eq("orig-9"), anyLong(), any(java.util.concurrent.TimeUnit.class));
        r.close();
    }

    @Test
    void ensureSinksOpenSkipsSinksAfterLifecycleClosed() throws Exception {
        AtomicReference<Boolean> opened = new AtomicReference<>(false);
        StreamSink<Object> sink = new StreamSink<>() {
            @Override
            public void invoke(Object value) {
            }

            @Override
            public void open() {
                opened.set(true);
            }
        };
        RedisPipelineRunner<Object> r = runner(realConfig, redisson, sink);
        Field closed = RedisPipelineRunner.class.getDeclaredField("sinksClosed");
        closed.setAccessible(true);
        closed.set(r, true);
        r.handle(msg(1_000L, "1-1"));
        assertEquals(false, opened.get(), "a closed runner must not open sinks again");
        r.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventTimeTimerOverflowWarnThrottlingArms() throws Exception {
        RedisRuntimeConfig cfg = cfg(b -> b.eventTimeTimerMaxSize(1));
        AtomicLong fired = new AtomicLong();
        RedisOperatorNode register = (value, ctx, emit) -> ctx.registerEventTimeTimer(Long.MAX_VALUE, fired::incrementAndGet);
        RedisPipelineRunner<Object> r = runner(cfg, redisson, v -> {
        }, register);

        Field timersF = RedisPipelineRunner.class.getDeclaredField("eventTimers");
        timersF.setAccessible(true);
        Field warnF = RedisPipelineRunner.class.getDeclaredField("lastEventTimeTimerOverflowWarnAtMs");
        warnF.setAccessible(true);
        AtomicLong warnAt = (AtomicLong) warnF.get(r);
        java.util.PriorityQueue<?> timers = (java.util.PriorityQueue<?>) timersF.get(r);

        r.handle(msg(1_000L, "1-1"));
        assertEquals(1, timers.size(), "first timer fits");
        r.handle(msg(1_000L, "1-2"));
        assertEquals(1, timers.size(), "overflowing registration is dropped");
        long firstWarn = warnAt.get();
        assertTrue(firstWarn > 0, "first overflow must log a warning");

        r.handle(msg(1_000L, "1-3"));
        assertEquals(firstWarn, warnAt.get(), "repeat overflow within a minute must not warn again");

        long stale = System.currentTimeMillis() - 120_000L;
        warnAt.set(stale);
        r.handle(msg(1_000L, "1-4"));
        assertTrue(warnAt.get() > stale, "warning is re-emitted after the throttle interval");
        r.close();
    }

    @Test
    void eventTimeTimerUnlimitedAndBelowCapacityArms() throws Exception {
        AtomicReference<Long> wm = new AtomicReference<>();
        RedisOperatorNode register = (value, ctx, emit) -> {
            ctx.registerEventTimeTimer(Long.MAX_VALUE, () -> {
            });
            ctx.registerEventTimeTimer(Long.MAX_VALUE, () -> {
            });
        };
        RedisPipelineRunner<Object> unlimited = runner(cfg(b -> b.eventTimeTimerMaxSize(0)), redisson, v -> {
        }, register);
        unlimited.handle(msg(1_000L, "1-1"));
        Field timersF = RedisPipelineRunner.class.getDeclaredField("eventTimers");
        timersF.setAccessible(true);
        assertEquals(2, ((java.util.PriorityQueue<?>) timersF.get(unlimited)).size());
        unlimited.close();

        RedisPipelineRunner<Object> belowCapacity = runner(cfg(b -> b.eventTimeTimerMaxSize(2)), redisson, v -> {
        }, register);
        belowCapacity.handle(msg(1_000L, "1-1"));
        assertEquals(2, ((java.util.PriorityQueue<?>) timersF.get(belowCapacity)).size());
        belowCapacity.close();
    }

    @Test
    void eventTimeTimerMaxSizeGetterFailureAndQueueMetricFailureAreSwallowed() throws Exception {
        RedisRuntimeConfig throwingMax = mock(RedisRuntimeConfig.class, delegatesTo(realConfig));
        when(throwingMax.getEventTimeTimerMaxSize()).thenThrow(new IllegalStateException("max down"));
        RedisOperatorNode register = (value, ctx, emit) -> ctx.registerEventTimeTimer(Long.MAX_VALUE, () -> {
        });
        RedisPipelineRunner<Object> r1 = runner(throwingMax, redisson, v -> {
        }, register);
        r1.handle(msg(1_000L, "1-1"));
        Field timersF = RedisPipelineRunner.class.getDeclaredField("eventTimers");
        timersF.setAccessible(true);
        assertEquals(1, ((java.util.PriorityQueue<?>) timersF.get(r1)).size(),
                "getter failure falls back to unlimited queue");
        r1.close();

        RedisRuntimeMetrics.setCollector(new SelectiveFailingMetricsCollector().failOn("setEventTimeTimerQueueSize"));
        RedisPipelineRunner<Object> r2 = runner(cfg(b -> b.eventTimeTimerMaxSize(1)), redisson, v -> {
        }, register);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> r2.handle(msg(1_000L, "1-1")));
        r2.close();
    }

    @Test
    void fireDueEventTimersRethrowsRuntimeExceptions() {
        RedisOperatorNode register = (value, ctx, emit) ->
                ctx.registerEventTimeTimer(1L, () -> {
                    throw new IllegalStateException("timer boom");
                });
        RedisPipelineRunner<Object> r = runner(realConfig, redisson, v -> {
        }, register);
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> r.handle(msg(5_000L, "1-1")));
        assertEquals("timer boom", e.getMessage());
        r.close();
    }
}
