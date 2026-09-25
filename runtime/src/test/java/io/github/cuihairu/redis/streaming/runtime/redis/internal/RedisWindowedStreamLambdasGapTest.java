package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
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
import java.time.Instant;
import java.util.ArrayList;
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
 * Accumulator/emitter lambda branches of {@code RedisWindowedStreamImpl} for apply/sum/count:
 * existing / blank / {@code "null"} / missing member state at accumulate time and blank / missing
 * member state at fire time, driven through a HashMap-backed {@code RMap} mock and a
 * TreeMap-backed due-set so windows fire exactly like against real Redis.
 */
class RedisWindowedStreamLambdasGapTest {

    private static final String D = "\u0001";
    private static final String MEMBER = "s:k" + D + "0" + D + "1000";

    private RedissonClient redis;
    private final Map<String, String> backing = new HashMap<>();
    private RMap<String, String> stateMap;
    private final TreeMap<Double, String> dueBacking = new TreeMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redis = mock(RedissonClient.class);
        stateMap = mock(RMap.class);
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

    private static RedisRuntimeConfig cfg() {
        return RedisRuntimeConfig.builder()
                .jobName("win-lambda-gap")
                .stateKeyPrefix("it-win-lambda-gap")
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

    @Test
    void applyAccumulatesAcrossRecordsAndFiresCollectedElements() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<Integer>ofMillis(1000))
                .apply((WindowFunction<String, Integer, String>) (key, window, elements, collector) -> {
                    List<Integer> seen = new ArrayList<>();
                    elements.forEach(seen::add);
                    collector.collect(key + ":" + seen);
                })
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            assertTrue(out.isEmpty(), "window must not fire while still open");
            runner.handle(msg(500L, 4));
            runner.handle(msg(1_500L, 9));
            assertEquals(List.of("k:[3, 4]"), out);
        } finally {
            runner.close();
        }
    }

    @Test
    void applyToleratesNullParsedListInStoredState() throws Exception {
        backing.put(MEMBER, "null");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<Integer>ofMillis(1000))
                .apply((WindowFunction<String, Integer, String>) (key, window, elements, collector) -> {
                    List<Integer> seen = new ArrayList<>();
                    elements.forEach(seen::add);
                    collector.collect(key + ":" + seen);
                })
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            runner.handle(msg(1_500L, 9));
            assertEquals(List.of("k:[3]"), out, "a JSON null list must be treated as empty history");
        } finally {
            runner.close();
        }
    }

    @Test
    void applySkipsBlankStoredStateAtAccumulateAndFireTime() throws Exception {
        backing.put(MEMBER, " ");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<Integer>ofMillis(1000))
                .apply((WindowFunction<String, Integer, String>) (key, window, elements, collector) ->
                        collector.collect("fired"))
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            backing.put(MEMBER, " ");
            runner.handle(msg(1_500L, 9));
            assertTrue(out.isEmpty(), "blank member state must be skipped at accumulate and fire time");
        } finally {
            runner.close();
        }
    }

    @Test
    void applyFireSkipsMissingMemberState() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<String>ofMillis(1000))
                .apply((WindowFunction<String, String, String>) (key, window, elements, collector) ->
                        collector.collect("fired"))
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, "a"));
            backing.clear();
            runner.handle(msg(1_500L, "c"));
            assertTrue(out.isEmpty(), "missing member state must not emit window results");
        } finally {
            runner.close();
        }
    }

    @Test
    void windowedSumAccumulatesFreshThenExistingState() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
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
            runner.handle(msg(500L, 4));
            runner.handle(msg(1_500L, 9));
            assertEquals(1, out.size());
            assertEquals(7, ((Number) out.get(0)).intValue());
        } finally {
            runner.close();
        }
    }

    @Test
    void windowedSumToleratesNullAndBlankStoredState() throws Exception {
        for (String seed : new String[]{"null", " "}) {
            backing.clear();
            backing.put(MEMBER, seed);
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
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
                runner.handle(msg(1_500L, 9));
                assertEquals(1, out.size());
                assertEquals(3, ((Number) out.get(0)).intValue(),
                        "seed=" + seed + " must be treated as empty history");
            } finally {
                runner.close();
            }
        }
    }

    @Test
    void windowedCountParsesExistingStateAndSkipsBlankAtFire() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<String>ofMillis(1000))
                .count()
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, "a"));
            runner.handle(msg(500L, "b"));
            runner.handle(msg(1_500L, "c"));
            assertEquals(1, out.size());
            assertEquals(2L, ((Number) out.get(0)).longValue());
        } finally {
            runner.close();
        }
    }

    @Test
    void windowedCountTreatsBlankStateAsZero() throws Exception {
        backing.put(MEMBER, " ");
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<String>ofMillis(1000))
                .count()
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, "a"));
            runner.handle(msg(1_500L, "c"));
            assertEquals(1, out.size());
            assertEquals(1L, ((Number) out.get(0)).longValue());
        } finally {
            runner.close();
        }
    }

    @Test
    void windowedCountFireSkipsBlankAndMissingState() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (String) m.getPayload())
                .keyBy(v -> "k")
                .window(TumblingWindow.<String>ofMillis(1000))
                .count()
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, "a"));
            backing.put(MEMBER, " ");
            runner.handle(msg(1_500L, "c"));
            assertTrue(out.isEmpty(), "blank count state must not fire a result");
        } finally {
            runner.close();
        }
    }
}
