package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keyed (non-window) instant operators of {@code RedisKeyedStreamBuilder}: {@code reduce}
 * (accumulate / null-result drop / corrupt-state and failing-reducer arms), the happy path of
 * {@code sum} and {@code getState}, driven through a HashMap-backed {@code RMap} mock so no
 * Redis is needed.
 */
class RedisKeyedStreamBuilderGapTest {

    private RedissonClient redis;
    private final Map<String, String> backing = new HashMap<>();

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
        when(due.add(anyDouble(), any())).thenReturn(true);
        when(due.firstEntry()).thenReturn(null);
        when(due.pollFirstEntry()).thenReturn(null);
    }

    private static Message msg(Object payload) {
        Message m = new Message();
        m.setId("m-" + payload);
        m.setTimestamp(Instant.ofEpochMilli(500L));
        m.setPayload(payload);
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    private static RedisRuntimeConfig cfg() {
        return RedisRuntimeConfig.builder()
                .jobName("keyed-gap")
                .stateKeyPrefix("it-keyed-gap")
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
    void keyedReduceAccumulatesPerKeyAndEmitsRunningResults() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .reduce((a, b) -> a + b)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(3));
            runner.handle(msg(5));
            assertEquals(List.of(3, 8), out);
        } finally {
            runner.close();
        }
    }

    @Test
    void keyedReduceDropsStoredStateWhenReducerReturnsNull() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .reduce((a, b) -> a == null ? b : null)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(3));
            assertTrue(backing.containsValue("3"), "first result stored for the key");
            runner.handle(msg(5));
            assertEquals(2, out.size(), "null reduce result is emitted and not stored");
            assertEquals(3, out.get(0));
            assertNull(out.get(1));
            assertTrue(!backing.containsValue("3"), "stored state removed with the null result");
        } finally {
            runner.close();
        }
    }

    @Test
    void keyedReduceFailsOnCorruptStoredState() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .reduce((a, b) -> a + b)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(3));
            backing.replaceAll((k, v) -> "not-json");
            RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.handle(msg(5)));
            assertEquals("Failed to deserialize reduce state", ex.getMessage());
        } finally {
            runner.close();
        }
    }

    @Test
    void keyedReduceWrapsFailingReducer() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .reduce((a, b) -> {
                    throw new IllegalStateException("boom");
                })
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(3));
            RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.handle(msg(5)));
            assertEquals("Reduce function failed", ex.getMessage());
            assertEquals("boom", ex.getCause().getMessage());
        } finally {
            runner.close();
        }
    }

    @Test
    void keyedSumEmitsRunningTotalsCastToTheValueType() throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .sum(v -> v)
                .addSink(out::add);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(3));
            runner.handle(msg(5));
            assertEquals(List.of(3, 8), out);
            assertEquals(Integer.class, out.get(1).getClass(), "result cast back to the element type");
        } finally {
            runner.close();
        }
    }

    @Test
    void getStateReturnsTheStoreBackedValueState() {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        KeyedStream<String, Integer> keyed = env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k");
        ValueState<String> state = keyed.getState(new StateDescriptor<>("st", String.class));
        assertEquals(RedisKeyedValueState.class, state.getClass());
    }
}
