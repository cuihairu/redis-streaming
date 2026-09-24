package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link RedisPipelineRunner}: sink lifecycle ({@code close}), sink
 * deduplication ({@code shouldInvokeSink}/{@code markSinkInvoked}/{@code stableMessageId}),
 * partition extraction, event-time timer ordering and the {@link RedisPipelineRunner.Context}
 * accessors/timer plumbing. No Redis required.
 */
class RedisPipelineRunnerLifecycleUnitTest {

    private static RedisRuntimeConfig cfg(String job, java.util.function.Consumer<RedisRuntimeConfig.Builder> tweaks) {
        RedisRuntimeConfig.Builder b = RedisRuntimeConfig.builder()
                .jobName(job)
                .stateKeyPrefix("it-rti-unit:" + job);
        tweaks.accept(b);
        return b.build();
    }

    private static Message msg(long eventTimeMs) {
        return msg(eventTimeMs, "id-" + eventTimeMs, null);
    }

    private static Message msg(long eventTimeMs, String id, Map<String, String> headers) {
        Message m = new Message();
        m.setId(id);
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload("p");
        m.setHeaders(headers);
        return m;
    }

    private static RedissonClient redisson() {
        return mock(RedissonClient.class);
    }

    @Test
    void contextAccessorsRaiseWatermarkAndEmitFromReachSinks() throws Exception {
        RedissonClient redis = redisson();
        ObjectMapper om = new ObjectMapper();
        RedisRuntimeConfig config = cfg("ctx", b -> {});
        List<Object> out = new CopyOnWriteArrayList<>();
        List<Object> seen = new CopyOnWriteArrayList<>();

        RedisOperatorNode probe = (value, ctx, emit) -> {
            seen.add(ctx.message());
            assertTrue(ctx.currentProcessingTime() > 0);
            assertSame(redis, ctx.redissonClient());
            assertSame(om, ctx.objectMapper());
            assertSame(config, ctx.runtimeConfig());
            ctx.raiseWatermark(12_345L);
            assertEquals(12_345L, ctx.currentWatermark());
            ctx.emitFrom(1, "skipped-to-op-1");
        };
        RedisOperatorNode tail = (value, ctx, emit) -> emit.emit(value);

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redis, om, "topicA", "groupA", List.of(probe, tail), List.of(out::add));
        try {
            Message m = msg(1000);
            runner.handle(m);
            assertEquals(List.of(m), seen);
            assertEquals(List.of("skipped-to-op-1"), out);
        } finally {
            runner.close();
        }
    }

    @Test
    void processingTimeTimerFiresAndWrapperSwallowsCallbackErrors() throws Exception {
        RedisRuntimeConfig config = cfg("ptimer", b -> {});
        RedissonClient redis = redisson();
        List<String> fired = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        RedisOperatorNode registrar = (value, ctx, emit) -> {
            long now = ctx.currentProcessingTime();
            ctx.registerProcessingTimeTimer(now, () -> {
                fired.add("ok");
                done.countDown();
                throw new IllegalStateException("timer-boom");
            });
            ctx.registerProcessingTimeTimer(now + 20, () -> {
                fired.add("second");
                done.countDown();
            });
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redis, new ObjectMapper(), "topicA", "groupA",
                List.of(registrar), List.of());
        try {
            runner.handle(msg(1000));
            assertTrue(done.await(5, TimeUnit.SECONDS), "timers should fire: " + fired);
            assertEquals(2, fired.size());
        } finally {
            runner.close();
        }
    }

    @Test
    void eventTimeTimersFireInTimestampThenSequenceOrder() throws Exception {
        RedisRuntimeConfig config = cfg("etimer", b -> b.watermarkOutOfOrderness(Duration.ZERO));
        RedissonClient redis = redisson();
        List<Long> fired = new CopyOnWriteArrayList<>();
        List<Long> registered = new CopyOnWriteArrayList<>();

        RedisOperatorNode registrar = (value, ctx, emit) -> {
            registered.add(ctx.currentEventTime());
            ctx.registerEventTimeTimer(1000L, () -> fired.add(1000L));
            ctx.registerEventTimeTimer(500L, () -> fired.add(500L));
            ctx.registerEventTimeTimer(500L, () -> fired.add(501L));
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redis, new ObjectMapper(), "topicA", "groupA",
                List.of(registrar), List.of());
        try {
            runner.handle(msg(2000));
            assertEquals(List.of(500L, 501L, 1000L), fired,
                    "same-timestamp timers break ties by registration sequence");
        } finally {
            runner.close();
        }
    }

    @Test
    void sinkDeduplicationSkipsAlreadyInvokedStableIds() throws Exception {
        RSetCache<String> set = mockSetCache();
        RedissonClient redis = redisson();
        doReturn(set).when(redis).getSetCache(anyString(), any());
        RedisRuntimeConfig config = cfg("dedup", b -> b
                .sinkDeduplicationEnabled(true)
                .sinkDeduplicationTtl(Duration.ofMinutes(5)));
        AtomicInteger invocations = new AtomicInteger();

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redis, new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of(v -> invocations.incrementAndGet()));
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put(MqHeaders.PARTITION_ID, "2");
            headers.put(MqHeaders.ORIGINAL_MESSAGE_ID, "orig-9");

            runner.handle(msg(1000, "inner-1", headers));
            runner.handle(msg(1000, "inner-2", headers));
            assertEquals(1, invocations.get(), "same original message id must be deduplicated");

            // no original id: falls back to message id
            Map<String, String> plainHeaders = Map.of(MqHeaders.PARTITION_ID, "2");
            runner.handle(msg(1001, "plain-1", plainHeaders));
            runner.handle(msg(1001, "plain-1", plainHeaders));
            assertEquals(2, invocations.get(), "stable id from getId() must be deduplicated");

            // blank id: dedup key unusable -> always invoke
            runner.handle(msg(1002, " ", plainHeaders));
            runner.handle(msg(1002, " ", plainHeaders));
            assertEquals(4, invocations.get());

            // no partition id -> dedup skipped
            runner.handle(msg(1003, "orig-9", Map.of(MqHeaders.ORIGINAL_MESSAGE_ID, "orig-9")));
            assertEquals(5, invocations.get());
        } finally {
            runner.close();
        }
    }

    @Test
    void sinkDedupZeroTtlUsesPlainAddAndFailuresAreTolerated() throws Exception {
        RSetCache<String> set = mockSetCache();
        RedissonClient redis = redisson();
        doReturn(set).when(redis).getSetCache(anyString(), any());
        RedisRuntimeConfig config = cfg("dedup2", b -> b
                .sinkDeduplicationEnabled(true)
                .sinkDeduplicationTtl(Duration.ZERO));
        AtomicInteger invocations = new AtomicInteger();

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redis, new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of(v -> invocations.incrementAndGet()));
        try {
            Map<String, String> headers = Map.of(MqHeaders.PARTITION_ID, "1", MqHeaders.ORIGINAL_MESSAGE_ID, "o1");
            runner.handle(msg(1000, "x", headers));
            runner.handle(msg(1000, "x", headers));
            assertEquals(1, invocations.get());
            verify(set, times(1)).add("o1");
            verify(set, never()).add(anyString(), anyLong(), any());

            // contains() failure -> treated as not-duplicate
            when(set.contains(any())).thenThrow(new IllegalStateException("redis down"));
            runner.handle(msg(1000, "x", headers));
            assertEquals(2, invocations.get());

            // add() failure must be swallowed
            doThrow(new IllegalStateException("redis down")).when(set).add(anyString());
            assertDoesNotThrow(() -> runner.handle(msg(1000, "x", headers)));
            assertEquals(3, invocations.get());
        } finally {
            runner.close();
        }
    }

    @Test
    void stableMessageIdToleratesHeaderAndIdFailures() throws Exception {
        RSetCache<String> set = mockSetCache();
        RedissonClient redis = redisson();
        doReturn(set).when(redis).getSetCache(anyString(), any());
        RedisRuntimeConfig config = cfg("dedup3", b -> b.sinkDeduplicationEnabled(true));
        AtomicInteger invocations = new AtomicInteger();

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redis, new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of(v -> invocations.incrementAndGet()));
        try {
            // headers.get(originalId) explodes -> stable id falls back to getId()
            Map<String, String> evilHeaders = new HashMap<>() {
                @Override
                public String get(Object key) {
                    if (MqHeaders.ORIGINAL_MESSAGE_ID.equals(key)) {
                        throw new IllegalStateException("headers-boom");
                    }
                    return super.get(key);
                }
            };
            evilHeaders.put(MqHeaders.PARTITION_ID, "1");
            Message m1 = msg(1000, "fallback-id", evilHeaders);
            runner.handle(m1);
            runner.handle(m1);
            assertEquals(1, invocations.get(), "header failure must fall back to message id");

            // getId() explodes -> stable id is null -> always invoke
            Message m2 = new Message() {
                @Override
                public String getId() {
                    throw new IllegalStateException("id-boom");
                }
            };
            m2.setTimestamp(Instant.ofEpochMilli(1000));
            m2.setHeaders(Map.of(MqHeaders.PARTITION_ID, "1"));
            runner.handle(m2);
            runner.handle(m2);
            assertEquals(3, invocations.get());
        } finally {
            runner.close();
        }
    }

    @Test
    void closeIsIdempotentAndClosesOpenedSinksOnly() throws Exception {
        RedisRuntimeConfig config = cfg("close", b -> {});
        List<String> events = new CopyOnWriteArrayList<>();
        StreamSink<Object> good = new StreamSink<>() {
            @Override
            public void open() {
                events.add("open-good");
            }

            @Override
            public void invoke(Object value) {
                events.add("invoke-good");
            }

            @Override
            public void close() {
                events.add("close-good");
            }
        };
        StreamSink<Object> bad = new StreamSink<>() {
            @Override
            public void open() {
                events.add("open-bad");
            }

            @Override
            public void invoke(Object value) {
            }

            @Override
            public void close() {
                events.add("close-bad");
                throw new IllegalStateException("close-boom");
            }
        };

        RedisPipelineRunner<Object> unopened = new RedisPipelineRunner<>(
                config, redisson(), new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of(good));
        unopened.close();
        assertEquals(List.of(), events, "close before open must not touch sinks");

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redisson(), new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of(good, bad));
        runner.handle(msg(1000));
        runner.close();
        runner.close();
        assertEquals(List.of("open-good", "open-bad", "invoke-good", "close-good", "close-bad"), events);
    }

    @Test
    void closeLeavesExternalTimerExecutorRunning() throws Exception {
        RedisRuntimeConfig config = cfg("close2", b -> {});
        ScheduledThreadPoolExecutor external = new ScheduledThreadPoolExecutor(1);
        try {
            RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                    config, redisson(), new ObjectMapper(), "topicA", "groupA",
                    List.of(), List.of(), external, false);
            runner.handle(msg(1000));
            runner.close();
            assertTrue(!external.isShutdown(), "executor owned by caller must survive close()");
        } finally {
            external.shutdownNow();
        }

        // default constructor owns its executor; close() must not throw
        RedisPipelineRunner<Object> owned = new RedisPipelineRunner<>(
                config, redisson(), new ObjectMapper(), "topicA", "groupA", List.of(), List.of());
        assertDoesNotThrow(owned::close);
    }

    @Test
    void onCheckpointAbortSwallowsSinkFailures() throws Exception {
        RedisRuntimeConfig config = cfg("abort", b -> {});
        AtomicInteger aborts = new AtomicInteger();
        CheckpointAwareSink<Object> sink = new CheckpointAwareSink<>() {
            @Override
            public void invoke(Object value) {
            }

            @Override
            public void onCheckpointAbort(long checkpointId, Throwable cause) {
                aborts.incrementAndGet();
                throw new IllegalStateException("abort-boom");
            }
        };
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redisson(), new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of(sink));
        try {
            assertDoesNotThrow(() -> runner.onCheckpointAbort(7L, new RuntimeException("x")));
            assertEquals(1, aborts.get());
        } finally {
            runner.close();
        }
    }

    @Test
    void extractPartitionIdHandlesAllHeaderShapes() throws Exception {
        AtomicInteger lastPartition = new AtomicInteger(999);
        RedisOperatorNode probe = (value, ctx, emit) -> {
            lastPartition.set(ctx.currentPartitionId());
            emit.emit(value);
        };
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg("pid", b -> {}), redisson(), new ObjectMapper(), "topicA", "groupA",
                List.of(probe), List.of());
        try {
            runner.handle(msg(1000, "a", Map.of(MqHeaders.PARTITION_ID, "7")));
            assertEquals(7, lastPartition.get());

            runner.handle(msg(1000, "b", Map.of(MqHeaders.PARTITION_ID, "  ")));
            assertEquals(-1, lastPartition.get());

            runner.handle(msg(1000, "c", Map.of(MqHeaders.PARTITION_ID, "not-a-number")));
            assertEquals(-1, lastPartition.get());

            runner.handle(msg(1000, "d", Map.of("other", "x")));
            assertEquals(-1, lastPartition.get());

            runner.handle(msg(1000, "e", null));
            assertEquals(-1, lastPartition.get());

            Method extract = RedisPipelineRunner.class.getDeclaredMethod("extractPartitionId", Message.class);
            extract.setAccessible(true);
            assertEquals(-1, extract.invoke(null, new Object[]{null}));
        } finally {
            runner.close();
        }
    }

    @Test
    void sinkDedupPrivateHelpersTolerateNullContext() throws Exception {
        RedisRuntimeConfig config = cfg("nullctx", b -> b.sinkDeduplicationEnabled(true));
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config, redisson(), new ObjectMapper(), "topicA", "groupA", List.of(), List.of());
        try {
            Method should = RedisPipelineRunner.class.getDeclaredMethod(
                    "shouldInvokeSink", int.class, RedisPipelineRunner.Context.class);
            should.setAccessible(true);
            assertEquals(true, should.invoke(runner, 0, null));

            Method mark = RedisPipelineRunner.class.getDeclaredMethod(
                    "markSinkInvoked", int.class, RedisPipelineRunner.Context.class);
            mark.setAccessible(true);
            mark.invoke(runner, 0, null);
        } finally {
            runner.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static RSetCache<String> mockSetCache() {
        RSetCache<String> set = mock(RSetCache.class);
        Set<String> backing = new HashSet<>();
        when(set.contains(any())).thenAnswer(inv -> backing.contains(inv.getArgument(0, Object.class)));
        when(set.add(anyString())).thenAnswer(inv -> backing.add(inv.getArgument(0, String.class)));
        when(set.add(anyString(), anyLong(), any())).thenAnswer(inv -> backing.add(inv.getArgument(0, String.class)));
        return set;
    }
}
