package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit coverage for the anonymous {@link KeyedProcessFunction.Context} of
 * {@code RedisKeyedStreamBuilder} (processing-time / event-time timer bodies and their
 * collectors) plus the keyed {@code map}/{@code process} lambdas. No Redis required.
 */
class RedisKeyedProcessTimerUnitTest {

    private static Message msg(long eventTimeMs, String payload) {
        Message m = new Message();
        m.setId("m-" + eventTimeMs);
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload(payload);
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    private static RedisRuntimeConfig cfg(String job) {
        return RedisRuntimeConfig.builder()
                .jobName(job)
                .stateKeyPrefix("it-rti-kpf:" + job)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static RedisPipelineDefinition definitionOf(Object stream) throws Exception {
        Field f = RedisStreamBuilder.class.getDeclaredField("registeredDefinition");
        f.setAccessible(true);
        return (RedisPipelineDefinition) f.get(stream);
    }

    @Test
    void keyedProcessTimersFireAndCollectThroughDownstream() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig config = cfg("timers");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);
        List<Object> out = new CopyOnWriteArrayList<>();
        CountDownLatch timersFired = new CountDownLatch(2);

        KeyedProcessFunction<String, String, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, String value, Context ctx, Collector<String> out) {
                assertTrue(ctx.currentProcessingTime() > 0);
                out.collect("p:" + value);
                ctx.registerProcessingTimeTimer(System.currentTimeMillis() + 30);
                ctx.registerEventTimeTimer(500L);
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                out.collect("proc:" + timestamp);
                timersFired.countDown();
            }

            @Override
            public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                out.collect("evt:" + timestamp);
                timersFired.countDown();
            }
        };

        Object tail = env.fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .process(fn)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            runner.handle(msg(1000, "x"));
            assertTrue(timersFired.await(5, TimeUnit.SECONDS), "timers should fire: " + out);
            assertEquals(List.of("p:x", "evt:500"), out.subList(0, 2));
            assertTrue(out.stream().anyMatch(v -> String.valueOf(v).startsWith("proc:")), "processing timer output missing: " + out);
        } finally {
            runner.close();
        }
    }

    @Test
    void processingTimeTimerBodyWrapsAndRethrowsCallbackFailures() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig config = cfg("ptimer-boom");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);
        List<Object> out = new CopyOnWriteArrayList<>();
        CountDownLatch fired = new CountDownLatch(1);

        KeyedProcessFunction<String, String, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, String value, Context ctx, Collector<String> out) {
                ctx.registerProcessingTimeTimer(System.currentTimeMillis());
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                fired.countDown();
                throw new IllegalStateException("onProcessingTime-boom");
            }
        };

        Object tail = env.fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .process(fn)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            runner.handle(msg(1000, "x"));
            assertTrue(fired.await(5, TimeUnit.SECONDS), "timer body should still run before failing");
        } finally {
            runner.close();
        }
    }

    @Test
    void eventTimeTimerFailurePropagatesThroughRunner() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig config = cfg("etimer-boom");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);

        KeyedProcessFunction<String, String, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, String value, Context ctx, Collector<String> out) {
                ctx.registerEventTimeTimer(500L);
            }

            @Override
            public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                throw new IllegalStateException("onEventTime-boom");
            }
        };

        Object tail = env.fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .process(fn)
                .addSink(v -> {});
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> runner.handle(msg(1000, "x")));
            assertTrue(e.getCause() instanceof IllegalStateException
                    && "onEventTime-boom".equals(e.getCause().getMessage()), "unexpected failure: " + e);
        } finally {
            runner.close();
        }
    }

    @Test
    void processCollectorWrapsDownstreamFailures() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig config = cfg("collector-boom");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);

        KeyedProcessFunction<String, String, String> fn = (key, value, ctx, out) -> out.collect(value);

        Object tail = env.fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .process(fn)
                .<String>map(v -> {
                    throw new IllegalStateException("downstream-boom");
                })
                .addSink(v -> {});
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> runner.handle(msg(1000, "x")));
            assertTrue(e.getCause() instanceof IllegalStateException
                    && "downstream-boom".equals(e.getCause().getMessage()), "unexpected failure: " + e);
        } finally {
            runner.close();
        }
    }

    @Test
    void keyedMapInheritedKeySelectorResolvesAndFailsWithoutKey() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig config = cfg("map-sel");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);
        List<Object> out = new CopyOnWriteArrayList<>();

        KeyedStream<String, String> mapped = env.fromMqTopic("topicA", "groupA")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> "k")
                .map(v -> v + "!");
        Object tail = mapped
                .map(v -> v + "?")
                .process((k, v, ctx, collector) -> collector.collect(v))
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
        try {
            runner.handle(msg(1000, "v"));
        } finally {
            runner.close();
        }
        assertEquals(List.of("v!?"), out);

        // inherited selector throws when no current key is bound (non-null path ran above)
        Field keySelector = mapped.getClass().getDeclaredField("keySelector");
        keySelector.setAccessible(true);
        @SuppressWarnings("unchecked")
        Function<Object, Object> selector = (Function<Object, Object>) keySelector.get(mapped);
        Field storeField = mapped.getClass().getDeclaredField("stateStore");
        storeField.setAccessible(true);
        RedisKeyedStateStore<String> store = (RedisKeyedStateStore<String>) storeField.get(mapped);
        store.clearCurrentKey();
        assertThrows(IllegalStateException.class, () -> selector.apply("anything"));
    }
}
