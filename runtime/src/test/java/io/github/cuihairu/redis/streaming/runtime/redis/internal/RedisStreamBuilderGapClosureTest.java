package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction.Collector;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Residual branches of {@code RedisStreamBuilder}: {@code print(null)} prefix defaulting,
 * {@code RedisWindowedStreamImpl} constructor lateness arms, keyed {@code sum} with a null value,
 * and the timer-collector wrappers that rethrow downstream failures as
 * {@link RuntimeException} (processing-time and event-time).
 */
class RedisStreamBuilderGapClosureTest {

    private static Message msg(long eventTimeMs, Object payload) {
        Message m = new Message();
        m.setId("m-" + eventTimeMs + "-" + System.nanoTime());
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload(payload);
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    private static RedisRuntimeConfig cfg() {
        return RedisRuntimeConfig.builder()
                .jobName("builder-gap")
                .stateKeyPrefix("it-builder-gap")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static RedisPipelineDefinition definitionOf(Object stream) throws Exception {
        Field f = RedisStreamBuilder.class.getDeclaredField("registeredDefinition");
        f.setAccessible(true);
        return (RedisPipelineDefinition) f.get(stream);
    }

    @Test
    void printAcceptsNullPrefix() {
        RedissonClient redis = mock(RedissonClient.class);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        assertNotNull(env.fromMqTopic("t", "g").print());
        assertNotNull(env.fromMqTopicWithId("src-2", "t", "g").print(null));
    }

    @Test
    void windowedCtorToleratesNullAndThrowingAllowedLateness() {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig real = cfg();
        RedisRuntimeConfig nullLateness = org.mockito.Mockito.mock(
                RedisRuntimeConfig.class, org.mockito.AdditionalAnswers.delegatesTo(real));
        org.mockito.Mockito.when(nullLateness.getWindowAllowedLateness()).thenReturn(null);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, nullLateness);
        WindowedStream<String, Integer> w = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.ofMillis(1000));
        assertNotNull(w);

        RedisRuntimeConfig throwing = org.mockito.Mockito.mock(
                RedisRuntimeConfig.class, org.mockito.AdditionalAnswers.delegatesTo(real));
        org.mockito.Mockito.when(throwing.getWindowAllowedLateness())
                .thenThrow(new IllegalStateException("lateness down"));
        RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(redis, throwing);
        assertNotNull(env2.fromMqTopic("t2", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.ofMillis(1000)));
    }

    @Test
    void keyedSumRejectsNullValuesWithExplicitMessage() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Object) null)
                .keyBy(v -> "k")
                .sum(v -> 1L)
                .addSink(v -> {
                });
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> runner.handle(msg(1_000L, "x")));
            assertTrue(e.getMessage().contains("null"), e.getMessage());
        } finally {
            runner.close();
        }
    }

    @Test
    void eventTimerCollectorWrapsDownstreamFailures() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        AtomicReference<Throwable> caught = new AtomicReference<>();
        KeyedProcessFunction<String, String, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, String value, Context ctx, Collector<String> out) {
                ctx.registerEventTimeTimer(500L);
            }

            @Override
            public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                try {
                    out.collect("boom");
                } catch (RuntimeException e) {
                    caught.set(e);
                }
            }
        };
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .process(fn)
                .addSink(v -> {
                    throw new IllegalStateException("sink down");
                });
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            runner.handle(msg(1_000L, "x"));
            assertNotNull(caught.get(), "collector must wrap downstream failures");
            assertTrue(caught.get().getCause() instanceof IllegalStateException);
            assertEquals("sink down", caught.get().getCause().getMessage());
        } finally {
            runner.close();
        }
    }

    @Test
    void processingTimerCollectorWrapsDownstreamFailures() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch fired = new CountDownLatch(1);
        KeyedProcessFunction<String, String, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, String value, Context ctx, Collector<String> out) {
                ctx.registerProcessingTimeTimer(System.currentTimeMillis());
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                try {
                    out.collect("boom");
                } catch (RuntimeException e) {
                    caught.set(e);
                } finally {
                    fired.countDown();
                }
            }
        };
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .process(fn)
                .addSink(v -> {
                    throw new IllegalStateException("sink down");
                });
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            runner.handle(msg(1_000L, "x"));
            assertTrue(fired.await(5, TimeUnit.SECONDS), "processing timer must fire");
            assertNotNull(caught.get(), "collector must wrap downstream failures");
            assertEquals("sink down", caught.get().getCause().getMessage());
        } finally {
            runner.close();
        }
    }
}
