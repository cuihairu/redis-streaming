package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.SelectiveFailingMetricsCollector;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deep coverage for {@code RedisWindowedStreamImpl}: all five window kinds fired through a
 * real-Redis pipeline, late-event dropping, multi-fire clamping, the sum value guard and the
 * error paths of the per-kind accumulate/emit lambdas (driven reflectively against real Redis
 * state maps with corrupt payloads).
 */
@Tag("integration")
class RedisWindowedStreamDeepIntegrationTest {

    private static final String D = "\u0001";
    private static final RedisPipelineRunner.Emitter IGNORE_OUT = out -> {};
    private static final WindowFunction<Object, Object, Object> NOOP_WF =
            (key, window, elements, collector) -> {};

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static RedisRuntimeConfig cfg(String prefix, java.util.function.Consumer<RedisRuntimeConfig.Builder> tweaks) {
        RedisRuntimeConfig.Builder b = RedisRuntimeConfig.builder()
                .jobName("it-rti-win-" + prefix)
                .stateKeyPrefix("it-rti-win:" + prefix)
                .windowMaxFiresPerRecord(8);
        tweaks.accept(b);
        return b.build();
    }

    private static Message msg(long eventTimeMs, Object payload) {
        Message m = new Message();
        m.setId("m-" + eventTimeMs + "-" + UUID.randomUUID().toString().substring(0, 6));
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload(payload);
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static RedisPipelineDefinition definitionOf(DataStream<?> stream) throws Exception {
        Field f = RedisStreamBuilder.class.getDeclaredField("registeredDefinition");
        f.setAccessible(true);
        return (RedisPipelineDefinition) f.get(stream);
    }

    /**
     * Accumulates two values in window [1000,2000) and fires it with a trigger at t=5000.
     */
    private static <T> List<T> fireWindow(RedissonClient redis, RedisRuntimeConfig config,
                                          Function<WindowedStream<Object, Object>, DataStream<T>> op) throws Exception {
        List<T> out = new CopyOnWriteArrayList<>();
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);
        String topic = "it-rti-topic-" + UUID.randomUUID().toString().substring(0, 8);
        KeyedStream<Object, Object> keyed = env.fromMqTopic(topic, "g")
                .map(m -> m.getPayload())
                .keyBy(v -> "k");
        DataStream<T> tail = op.apply(keyed.window(TumblingWindow.ofMillis(1000)));
        RedisPipelineRunner<Object> runner = definitionOf(tail.addSink(out::add)).freeze().buildRunner();
        try {
            runner.handle(msg(1500, 3));
            runner.handle(msg(1600, 4));
            runner.handle(msg(5000, 999));
        } finally {
            runner.close();
        }
        return out;
    }

    @Test
    void countWindowFiresThroughRealRedis() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Long> out = fireWindow(redis, cfg(uid, b -> {}), w -> w.count());
            assertEquals(List.of(2L), out);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void sumWindowFiresThroughRealRedis() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Object> out = fireWindow(redis, cfg(uid, b -> {}), w -> w.sum(v -> (Number) v));
            assertEquals(List.of(7), out);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void reduceWindowFiresThroughRealRedis() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Object> out = fireWindow(redis, cfg(uid, b -> {}),
                    w -> w.reduce((ReduceFunction<Object>) (a, b) -> (Integer) a + (Integer) b));
            assertEquals(List.of(7), out);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void aggregateWindowFiresThroughRealRedis() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Integer> out = fireWindow(redis, cfg(uid, b -> {}), w -> w.aggregate(new SumAggregate()));
            assertEquals(List.of(7), out);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void applyWindowDecodesStringKeyAndWindowBounds() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<String> out = fireWindow(redis, cfg(uid, b -> {}),
                    w -> w.apply((WindowFunction<Object, Object, String>) (key, window, elements, collector) -> {
                        List<Object> seen = new ArrayList<>();
                        for (Object e : elements) {
                            seen.add(e);
                        }
                        collector.collect(key + "|" + window.getStart() + "-" + window.getEnd() + "|" + seen);
                    }));
            assertEquals(List.of("k|1000-2000|[3, 4]"), out);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void applyWindowDecodesNumericAndJsonKeys() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String uid2 = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Object> longOut = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment e1 =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid, b -> {}));
            KeyedStream<Object, Object> k1 = e1.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> (Object) 42);
            DataStream<String> t1 = k1
                    .window(TumblingWindow.ofMillis(1000))
                    .apply((WindowFunction<Object, Object, String>) (key, window, elements, collector) ->
                            collector.collect(key.getClass().getSimpleName() + ":" + key))
                    .addSink(longOut::add);
            RedisPipelineRunner<Object> r1 = definitionOf(t1).freeze().buildRunner();
            try {
                r1.handle(msg(1500, 1));
                r1.handle(msg(5000, 2));
            } finally {
                r1.close();
            }
            assertEquals(List.of("Long:42"), longOut);

            List<Object> jsonOut = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment e2 =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid2, b -> {}));
            KeyedStream<Object, Object> k2 = e2.fromMqTopic("it-rti-t-" + uid2, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> (Object) new WidgetKey("k"));
            DataStream<String> t2 = k2
                    .window(TumblingWindow.ofMillis(1000))
                    .apply((WindowFunction<Object, Object, String>) (key, window, elements, collector) ->
                            collector.collect(((WidgetKey) key).getName()))
                    .addSink(jsonOut::add);
            RedisPipelineRunner<Object> r2 = definitionOf(t2).freeze().buildRunner();
            try {
                r2.handle(msg(1500, 1));
                r2.handle(msg(5000, 2));
            } finally {
                r2.close();
            }
            assertEquals(List.of("k"), jsonOut);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.getKeys().deleteByPattern("it-rti-win:" + uid2 + "*");
            redis.shutdown();
        }
    }

    @Test
    void lateEventsAreDroppedAfterWindowClosed() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Long> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid, b -> {}));
            DataStream<Long> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .window(TumblingWindow.ofMillis(1000))
                    .count()
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                runner.handle(msg(1500, "a"));
                runner.handle(msg(5000, "b"));
                assertEquals(List.of(1L), out, "window [1000,2000) must fire with count 1");
                runner.handle(msg(1500, "late"));
                runner.handle(msg(9000, "c"));
                assertEquals(List.of(1L, 1L), out, "late event must not resurrect the closed window: " + out);
            } finally {
                runner.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void multiFireIsClampedPerRecord() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Long> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis,
                    cfg(uid, b -> b.windowMaxFiresPerRecord(2)
                            .watermarkOutOfOrderness(Duration.ofMillis(2000))));
            DataStream<Long> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .window(TumblingWindow.ofMillis(1000))
                    .count()
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                // out-of-orderness keeps the watermark behind so all three windows become
                // due on the trigger record at once
                runner.handle(msg(500, "a"));
                runner.handle(msg(1500, "b"));
                runner.handle(msg(2500, "c"));
                runner.handle(msg(9000, "d"));
                assertEquals(2, out.size(), "only two windows may fire per record: " + out);
                runner.handle(msg(9001, "e"));
                assertEquals(3, out.size(), "leftover window fires on the next record: " + out);
            } finally {
                runner.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void sumGuardRejectsNonNumberElements() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid, b -> {}));
            DataStream<Object> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .window(TumblingWindow.ofMillis(1000))
                    .sum(v -> (Number) v)
                    .addSink(v -> {});
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                assertThrows(UnsupportedOperationException.class,
                        () -> runner.handle(msg(1500, "not-a-number")));
            } finally {
                runner.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void nullKeyAndNullValueStillAccumulate() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Long> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid, b -> {}));
            DataStream<Long> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> null)
                    .window(TumblingWindow.ofMillis(1000))
                    .count()
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                runner.handle(msg(1500, null));
                runner.handle(msg(5000, null));
                assertEquals(List.of(1L), out);
            } finally {
                runner.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void reduceLambdaErrorPaths() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "reduce-err";
        String member = "s:k" + D + "1000" + D + "2000";
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);
            RScoredSortedSet<String> due = dueOf(redis, uid);
            seedValueClass(w, Integer.class);

            ref.map().put(member, "not-json");
            RuntimeException e1 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$reduce$2", (ReduceFunction<Object>) (a, b) -> a,
                            ref, stateName, member, 1, due, 2000L));
            assertTrue(e1.getMessage().contains("Failed to deserialize window reduce state"), "" + e1);

            // reducer only runs against existing state
            ref.map().put(member, "1");
            RuntimeException e2 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$reduce$2",
                            (ReduceFunction<Object>) (a, b) -> {
                                throw new IllegalStateException("reduce-boom");
                            }, ref, stateName, member, 2, due, 2000L));
            assertTrue(e2.getMessage().contains("Window reduce function failed"), "" + e2);

            ref.map().delete();
            RuntimeException e3 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$reduce$2", (ReduceFunction<Object>) (a, b) -> a,
                            ref, stateName, member, new Evil(), due, 2000L));
            assertTrue(e3.getMessage().contains("Failed to serialize window reduce state"), "" + e3);

            ref.map().put(member, "1");
            due.add(2000D, member);
            invoke(w, "lambda$reduce$2", (ReduceFunction<Object>) (a, b) -> null,
                    ref, stateName, member, 2, due, 2000L);
            assertNull(ref.map().get(member));
            assertEquals(0, due.size());

            ref.map().put(member, "not-json");
            RuntimeException e4 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$reduce$3", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e4.getMessage().contains("Failed to emit window reduce result"), "" + e4);

            ref.map().delete();
            invoke(w, "lambda$reduce$3", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void aggregateLambdaErrorPaths() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "agg-err";
        String member = "s:k" + D + "1000" + D + "2000";
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);
            RScoredSortedSet<String> due = dueOf(redis, uid);

            ref.map().put(member, "not-json");
            RuntimeException e1 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$aggregate$4", new SumAggregate(), SumAggregate.Sum.class,
                            ref, stateName, member, 1, due, 2000L));
            assertTrue(e1.getMessage().contains("Failed to deserialize window accumulator"), "" + e1);

            AggregateFunction<Object, Object> addFails = new AggregateFunction<>() {
                @Override
                public Accumulator<Object> createAccumulator() {
                    return new SumAggregate.Sum();
                }

                @Override
                public Accumulator<Object> add(Object value, Accumulator<Object> accumulator) {
                    throw new IllegalStateException("add-boom");
                }

                @Override
                public Object getResult(Accumulator<Object> accumulator) {
                    return null;
                }

                @Override
                public Accumulator<Object> merge(Accumulator<Object> a, Accumulator<Object> b) {
                    return a;
                }
            };
            ref.map().delete();
            RuntimeException e2 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$aggregate$4", addFails, SumAggregate.Sum.class,
                            ref, stateName, member, 1, due, 2000L));
            assertTrue(e2.getMessage().contains("Window aggregate add failed"), "" + e2);

            AggregateFunction<Object, Object> serFails = new AggregateFunction<>() {
                @Override
                public Accumulator<Object> createAccumulator() {
                    return new SumAggregate.Sum();
                }

                @Override
                public Accumulator<Object> add(Object value, Accumulator<Object> accumulator) {
                    return new EvilAcc();
                }

                @Override
                public Object getResult(Accumulator<Object> accumulator) {
                    return null;
                }

                @Override
                public Accumulator<Object> merge(Accumulator<Object> a, Accumulator<Object> b) {
                    return a;
                }
            };
            RuntimeException e3 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$aggregate$4", serFails, EvilAcc.class,
                            ref, stateName, member, 1, due, 2000L));
            assertTrue(e3.getMessage().contains("Failed to serialize window accumulator"), "" + e3);

            ref.map().put(member, "not-json");
            RuntimeException e4 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$aggregate$5", SumAggregate.Sum.class, new SumAggregate(),
                            ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e4.getMessage().contains("Failed to emit window aggregate result"), "" + e4);

            ref.map().delete();
            invoke(w, "lambda$aggregate$5", SumAggregate.Sum.class, new SumAggregate(),
                    ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void applyLambdaErrorPaths() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "apply-err";
        String member = "s:k" + D + "1000" + D + "2000";
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);
            RScoredSortedSet<String> due = dueOf(redis, uid);

            ref.map().put(member, "not-json");
            RuntimeException e1 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$apply$6", ref, stateName, member, 1, due, 2000L));
            assertTrue(e1.getMessage().contains("Failed to deserialize window elements"), "" + e1);

            ref.map().delete();
            RuntimeException e2 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$apply$6", ref, stateName, member, new Evil(), due, 2000L));
            assertTrue(e2.getMessage().contains("Failed to serialize window elements"), "" + e2);

            seedValueClass(w, String.class);

            ref.map().put(member, " ");
            invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            ref.map().put(member, "not-json");
            RuntimeException e3 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e3.getMessage().contains("Failed to deserialize window elements"), "" + e3);

            ref.map().put(member, "[\"not-json\"]");
            RuntimeException e4 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e4.getMessage().contains("Failed to deserialize window element"), "" + e4);

            ref.map().put(member, "[\"\\\"x\\\"\"]");
            RuntimeException e5 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$apply$7",
                            (WindowFunction<Object, Object, Object>) (key, window, elements, collector) -> {
                                throw new IllegalStateException("wf-boom");
                            }, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e5.getMessage().contains("Window function failed"), "" + e5);

            // no recorded value class -> silent no-op
            seedValueClass(w, null);
            ref.map().put(member, "[\"\\\"x\\\"\"]");
            invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void sumAndCountLambdaErrorPaths() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "sum-err";
        String member = "s:k" + D + "1000" + D + "2000";
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);
            RScoredSortedSet<String> due = dueOf(redis, uid);

            assertThrows(UnsupportedOperationException.class, () -> invoke(w, "lambda$sum$8", "str"));
            invoke(w, "lambda$sum$8", 5);

            Function<Object, Number> fieldSelector = v -> (Number) v;
            ref.map().put(member, "not-json");
            RuntimeException e1 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$sum$9", fieldSelector, ref, stateName, member, 5, due, 2000L));
            assertTrue(e1.getMessage().contains("Failed to deserialize window sum state"), "" + e1);

            ref.map().put(member, "not-json");
            RuntimeException e2 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$sum$10", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e2.getMessage().contains("Failed to emit window sum result"), "" + e2);

            ref.map().delete();
            ref.map().put(member, "abc");
            invoke(w, "lambda$count$11", ref, stateName, member, 1, due, 2000L);
            assertEquals("1", ref.map().get(member));

            ref.map().put(member, "xx");
            RuntimeException e3 = assertThrows(RuntimeException.class,
                    () -> invoke(w, "lambda$count$12", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT));
            assertTrue(e3.getMessage().contains("Failed to emit window count result"), "" + e3);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void registerWindowedOperatorNullWindowsAndLateDropMetricFailures() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            List<Long> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid, b -> {}));
            DataStream<Long> tail = env.fromMqTopic("it-rti-t-" + uid, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .window(new NullWindowAssigner())
                    .count()
                    .addSink(out::add);
            RedisPipelineRunner<Object> runner = definitionOf(tail).freeze().buildRunner();
            try {
                runner.handle(msg(1500, "a"));
                assertTrue(out.isEmpty(), "null windows must be skipped");
            } finally {
                runner.close();
            }

            List<Long> lateOut = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env2 =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid + "-late", b -> {}));
            DataStream<Long> lateTail = env2.fromMqTopic("it-rti-t-" + uid + "-late", "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> "k")
                    .window(TumblingWindow.ofMillis(1000))
                    .count()
                    .addSink(lateOut::add);
            RedisPipelineRunner<Object> runner2 = definitionOf(lateTail).freeze().buildRunner();
            RedisRuntimeMetricsCollector previous = RedisRuntimeMetrics.get();
            RedisRuntimeMetrics.setCollector(
                    new SelectiveFailingMetricsCollector().failOn("incWindowLateDropped"));
            try {
                runner2.handle(msg(1500, "a"));
                runner2.handle(msg(5000, "b"));
                assertEquals(List.of(1L), lateOut);
                runner2.handle(msg(1500, "late"));
                assertEquals(List.of(1L), lateOut, "late drop must survive the metric failure");
            } finally {
                RedisRuntimeMetrics.setCollector(previous);
                runner2.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void sumGuardNullElementAndAccumulateSerializeFailure() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String uid2 = UUID.randomUUID().toString().substring(0, 8);
        try {
            WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> invoke(w, "lambda$sum$8", (Object) null));
            assertTrue(e.getMessage().contains("null"), "" + e);

            ObjectMapper failingMapper = org.mockito.Mockito.spy(new ObjectMapper());
            org.mockito.Mockito.doThrow(new IllegalStateException("serialize down"))
                    .when(failingMapper).writeValueAsString(org.mockito.ArgumentMatchers.any());
            RedisStreamExecutionEnvironment env =
                    RedisStreamExecutionEnvironment.create(redis, cfg(uid2, b -> {}));
            Field mapperField = RedisStreamExecutionEnvironment.class.getDeclaredField("objectMapper");
            mapperField.setAccessible(true);
            mapperField.set(env, failingMapper);
            WindowedStream<Object, Object> w2 = env.fromMqTopic("it-rti-t-" + uid2, "g")
                    .map(m -> m.getPayload())
                    .keyBy(v -> v)
                    .window(TumblingWindow.ofMillis(1000));

            String stateName = "sum-ser";
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid2, stateName);
            RScoredSortedSet<String> due = dueOf(redis, uid2);
            ref.map().delete();
            Function<Object, Number> fieldSelector = v -> (Number) v;
            RuntimeException e2 = assertThrows(RuntimeException.class,
                    () -> invoke(w2, "lambda$sum$9", fieldSelector, ref, stateName, "member",
                            5, due, 2000L));
            assertTrue(e2.getMessage().contains("Failed to serialize window sum state"), "" + e2);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.getKeys().deleteByPattern("it-rti-win:" + uid2 + "*");
            redis.shutdown();
        }
    }

    @Test
    void sumAndCountEmitterResidualArms() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "sum-emit-res";
        String member = "s:k" + D + "1000" + D + "2000";
        RedisRuntimeMetricsCollector previous = RedisRuntimeMetrics.get();
        SelectiveFailingMetricsCollector failing = new SelectiveFailingMetricsCollector();
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);

            ref.map().delete();
            invoke(w, "lambda$sum$10", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);
            ref.map().put(member, " ");
            invoke(w, "lambda$sum$10", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            ref.map().put(member, "null");
            invoke(w, "lambda$sum$10", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            ref.map().put(member, "{\"sum\":1,\"sample\":\"java.lang.Integer\"}");
            failing.failOn("incWindowFired");
            RedisRuntimeMetrics.setCollector(failing);
            invoke(w, "lambda$sum$10", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            RedisKeyedStateStore.StateMapRef countRef = refOf(redis, uid, "count-emit-res");
            countRef.map().delete();
            invoke(w, "lambda$count$12", countRef, "count-emit-res", member, 1000L, 2000L, 0, IGNORE_OUT);
            countRef.map().put(member, "3");
            invoke(w, "lambda$count$12", countRef, "count-emit-res", member, 1000L, 2000L, 0, IGNORE_OUT);
        } finally {
            RedisRuntimeMetrics.setCollector(previous);
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void emitterMetricFailureArmsForReduceAggregateApply() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "emit-metric";
        String member = "s:k" + D + "1000" + D + "2000";
        RedisRuntimeMetricsCollector previous = RedisRuntimeMetrics.get();
        RedisRuntimeMetrics.setCollector(
                new SelectiveFailingMetricsCollector().failOn("incWindowFired"));
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);

            seedValueClass(w, null);
            ref.map().put(member, "1");
            invoke(w, "lambda$reduce$3", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            seedValueClass(w, Integer.class);
            ref.map().put(member, "1");
            invoke(w, "lambda$reduce$3", ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            ref.map().put(member, "{\"sum\":3}");
            invoke(w, "lambda$aggregate$5", SumAggregate.Sum.class, new SumAggregate(),
                    ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            seedValueClass(w, String.class);
            ref.map().put(member, "[\"\\\"x\\\"\"]");
            invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);
        } finally {
            RedisRuntimeMetrics.setCollector(previous);
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void applyEmitterResidualArms() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        WindowedStream<Object, Object> w = windowed(redis, cfg(uid, b -> {}));
        String stateName = "apply-res";
        String member = "s:k" + D + "1000" + D + "2000";
        try {
            RedisKeyedStateStore.StateMapRef ref = refOf(redis, uid, stateName);
            seedValueClass(w, String.class);

            ref.map().put(member, "null");
            invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);

            ref.map().put(member, "[null]");
            invoke(w, "lambda$apply$7", NOOP_WF, ref, stateName, member, 1000L, 2000L, 0, IGNORE_OUT);
        } finally {
            redis.getKeys().deleteByPattern("it-rti-win:" + uid + "*");
            redis.shutdown();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static RedisKeyedStateStore.StateMapRef refOf(RedissonClient redis, String uid, String stateName) {
        String key = "it-rti-win:" + uid + ":lambda:" + stateName;
        return new RedisKeyedStateStore.StateMapRef(key, redis.getMap(key, StringCodec.INSTANCE));
    }

    private static RScoredSortedSet<String> dueOf(RedissonClient redis, String uid) {
        return redis.getScoredSortedSet("it-rti-win:" + uid + ":due", StringCodec.INSTANCE);
    }

    private static WindowedStream<Object, Object> windowed(RedissonClient redis, RedisRuntimeConfig config) {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, config);
        return env.fromMqTopic("it-rti-t-" + UUID.randomUUID().toString().substring(0, 8), "g")
                .map(m -> m.getPayload())
                .keyBy(v -> v)
                .window(TumblingWindow.ofMillis(1000));
    }

    @SuppressWarnings("unchecked")
    private static void seedValueClass(WindowedStream<?, ?> w, Class<?> valueClass) throws Exception {
        Field valueClassRef = w.getClass().getDeclaredField("valueClassRef");
        valueClassRef.setAccessible(true);
        ((AtomicReference<Class<?>>) valueClassRef.get(w)).set(valueClass);
    }

    private static Object invoke(Object target, String lambdaName, Object... args) throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(lambdaName) && compatible(m, args)) {
                m.setAccessible(true);
                try {
                    return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof Exception ex) {
                        throw ex;
                    }
                    throw e;
                }
            }
        }
        throw new AssertionError(lambdaName + " not found on " + target.getClass());
    }

    private static boolean compatible(Method m, Object[] args) {
        if (m.getParameterCount() != args.length) {
            return false;
        }
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !boxed(types[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        return Map.of(boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
                int.class, Integer.class, long.class, Long.class, float.class, Float.class,
                double.class, Double.class, char.class, Character.class).get(c);
    }

    public static final class Evil {
        public String getBoom() {
            throw new IllegalStateException("no-serialization");
        }
    }

    public static final class EvilNumber extends Number {
        @Override
        public int intValue() {
            throw new IllegalStateException("no-serialization");
        }

        @Override
        public long longValue() {
            throw new IllegalStateException("no-serialization");
        }

        @Override
        public float floatValue() {
            throw new IllegalStateException("no-serialization");
        }

        @Override
        public double doubleValue() {
            throw new IllegalStateException("no-serialization");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("no-serialization");
        }
    }

    public static final class NullWindowAssigner implements WindowAssigner<Object> {
        @Override
        public Iterable<Window> assignWindows(Object element, long timestamp) {
            return java.util.Collections.singletonList(null);
        }

        @Override
        public Trigger<Object> getDefaultTrigger() {
            return null;
        }
    }

    public static final class EvilAcc implements AggregateFunction.Accumulator<Object> {
        public String getBoom() {
            throw new IllegalStateException("no-serialization");
        }
    }

    public static final class WidgetKey {
        private String name;

        public WidgetKey() {
        }

        public WidgetKey(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static final class SumAggregate implements AggregateFunction<Object, Integer> {
        public static final class Sum implements AggregateFunction.Accumulator<Object> {
            private int sum;

            public int getSum() {
                return sum;
            }

            public void setSum(int sum) {
                this.sum = sum;
            }
        }

        @Override
        public Accumulator<Object> createAccumulator() {
            return new Sum();
        }

        @Override
        public Accumulator<Object> add(Object value, Accumulator<Object> accumulator) {
            Sum sum = (Sum) accumulator;
            sum.sum += (Integer) value;
            return sum;
        }

        @Override
        public Integer getResult(Accumulator<Object> accumulator) {
            return ((Sum) accumulator).sum;
        }

        @Override
        public Accumulator<Object> merge(Accumulator<Object> a, Accumulator<Object> b) {
            ((Sum) a).sum += ((Sum) b).sum;
            return a;
        }
    }
}
