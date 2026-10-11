package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Windowed {@code aggregate()} (accumulator JSON round-trip + result fire) and the RT-M3
 * idle-flush drain (the {@code addIdleFlushHook} lambda registered by windowed operators),
 * driven through a HashMap-backed {@code RMap} mock and a TreeMap-backed due set.
 */
class RedisWindowedStreamAggregateGapTest {

    private RedissonClient redis;
    private final Map<String, String> backing = new HashMap<>();
    private final TreeMap<Double, String> dueBacking = new TreeMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redis = mock(RedissonClient.class);
        RMap<String, String> stateMap = mock(RMap.class);
        RSet<String> index = mock(RSet.class);
        RScoredSortedSet<String> due = mock(RScoredSortedSet.class);
        when(redis.getMap(anyString(), any(Codec.class))).thenReturn((RMap) stateMap);
        when(redis.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redis.getScoredSortedSet(anyString(), any(Codec.class))).thenReturn((RScoredSortedSet) due);
        when(stateMap.get(any())).thenAnswer(inv -> backing.get(inv.getArgument(0, String.class)));
        when(stateMap.put(any(), any())).thenAnswer(inv ->
                backing.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class)));
        when(stateMap.remove(any())).thenAnswer(inv -> backing.remove(inv.getArgument(0, String.class)));
        when(due.add(anyDouble(), any())).thenAnswer(inv -> {
            dueBacking.put(inv.getArgument(0, Double.class), inv.getArgument(1, String.class));
            return true;
        });
        when(due.remove(any())).thenReturn(true);
        when(due.firstEntry()).thenAnswer(inv -> dueBacking.isEmpty() ? null
                : new ScoredEntry<>(dueBacking.firstKey(), dueBacking.firstEntry().getValue()));
        when(due.pollFirstEntry()).thenAnswer(inv -> {
            Map.Entry<Double, String> e = dueBacking.pollFirstEntry();
            return e == null ? null : new ScoredEntry<>(e.getKey(), e.getValue());
        });
    }

    private static Message msg(long eventTimeMs, Object payload) {
        Message m = new Message();
        m.setId("m-" + eventTimeMs + "-" + payload);
        m.setTimestamp(Instant.ofEpochMilli(eventTimeMs));
        m.setPayload(payload);
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    private static RedisRuntimeConfig cfg(Duration idleTimeout) {
        return RedisRuntimeConfig.builder()
                .jobName("win-agg-gap")
                .stateKeyPrefix("it-win-agg-gap")
                .watermarkIdleTimeout(idleTimeout)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static RedisPipelineDefinition definitionOf(Object stream) throws Exception {
        Field f = RedisStreamBuilder.class.getDeclaredField("registeredDefinition");
        f.setAccessible(true);
        return (RedisPipelineDefinition) f.get(stream);
    }

    private RedisPipelineRunner<Object> runnerFor(Object tail) throws Exception {
        return definitionOf(tail).freeze().buildRunner();
    }

    private static void idleSweep(RedisPipelineRunner<?> runner) throws Exception {
        Method m = RedisPipelineRunner.class.getDeclaredMethod("idleSweep");
        m.setAccessible(true);
        m.invoke(runner);
    }

    /** Counting accumulator with a plain public field shape Jackson round-trips. */
    public static class CountAcc implements AggregateFunction.Accumulator<Integer> {
        private int n;

        public int getN() {
            return n;
        }

        public void setN(int n) {
            this.n = n;
        }
    }

    @Test
    void aggregateAccumulatesThroughSerializedStateAndFiresTheResult() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg(null));
        List<Object> out = new CopyOnWriteArrayList<>();
        AggregateFunction<Integer, String> fn = new AggregateFunction<>() {
            @Override
            public AggregateFunction.Accumulator<Integer> createAccumulator() {
                return new CountAcc();
            }

            @Override
            public AggregateFunction.Accumulator<Integer> add(Integer value, AggregateFunction.Accumulator<Integer> acc) {
                CountAcc c = (CountAcc) acc;
                c.setN(c.getN() + value);
                return c;
            }

            @Override
            public String getResult(AggregateFunction.Accumulator<Integer> acc) {
                return "sum=" + ((CountAcc) acc).getN();
            }

            @Override
            public AggregateFunction.Accumulator<Integer> merge(AggregateFunction.Accumulator<Integer> a,
                                                                AggregateFunction.Accumulator<Integer> b) {
                CountAcc c = new CountAcc();
                c.setN(((CountAcc) a).getN() + ((CountAcc) b).getN());
                return c;
            }
        };
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<Integer>ofMillis(1000))
                .aggregate(fn)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            assertTrue(out.isEmpty(), "window must not fire while still open");
            runner.handle(msg(500L, 4));
            runner.handle(msg(1_500L, 9));
            assertEquals(List.of("sum=7"), out, "accumulator restored from JSON between records");
        } finally {
            runner.close();
        }
    }

    @Test
    void idleFlushDrainsRemainingWindowsWithoutNewRecords() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg(Duration.ofMillis(60)));
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<Integer>ofMillis(1000))
                .sum(v -> v)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            assertTrue(out.isEmpty(), "no fire while the window is open and the source is quiet");
            Thread.sleep(120); // past watermarkIdleTimeout: the sweep must raise the watermark
            idleSweep(runner);
            assertEquals(List.of(3), out, "idle flush fires the due window");
        } finally {
            runner.close();
        }
    }
}
