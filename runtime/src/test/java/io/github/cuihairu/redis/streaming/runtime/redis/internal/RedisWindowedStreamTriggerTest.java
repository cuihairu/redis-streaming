package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import io.github.cuihairu.redis.streaming.window.triggers.EventTimeTrigger;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Window trigger wiring (todo B3 / the former dead interface): the Redis window operators
 * must consult {@link WindowAssigner#getDefaultTrigger()} — one fresh trigger instance per
 * (partition, key, window) bucket — on every element ({@code onElement}) and at the
 * close-time fire decision ({@code onEventTime}).
 *
 * <p>The stock default trigger ({@link EventTimeTrigger}) answers CONTINUE on element and
 * FIRE_AND_PURGE at close, which is exactly the pre-wiring behavior; the equivalence test
 * pins that. Custom triggers gain early partial fires, early close-outs, deferral and purge.
 *
 * <p>Driven through the same mock-Redisson harness as
 * {@code RedisWindowedStreamLambdasGapTest}: a TreeMap-backed due set fires windows exactly
 * like real Redis, {@code RedisPipelineRunner#handle} processes records synchronously.
 */
class RedisWindowedStreamTriggerTest {

    private static final String D = "\u0001";
    private static final String MEMBER = "e:s:k" + D + "0" + D + "1000";

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
        // RScoredSortedSet.remove(Object) removes by VALUE, not by score-key
        when(due.remove(any())).thenAnswer(inv -> dueBacking.values().remove(inv.getArgument(0, String.class)));
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
                .jobName("win-trigger")
                .stateKeyPrefix("it-win-trigger")
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

    /** Sum-reduce over keyed ints with a custom-triggered tumbling window. */
    private Object triggeredSumStream(Supplier<WindowAssigner.Trigger<Integer>> triggerFactory,
                                      AtomicInteger factoryCalls,
                                      List<Object> out) {
        TumblingWindow<Integer> tumbling = TumblingWindow.ofMillis(1000);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg());
        return env.fromMqTopic("t", "g")
                .map(m -> (Integer) m.getPayload())
                .keyBy(v -> "k")
                .window(new WindowAssigner<Integer>() {
                    @Override
                    public Iterable<Window> assignWindows(Integer element, long timestamp) {
                        return tumbling.assignWindows(element, timestamp);
                    }

                    @Override
                    public Trigger<Integer> getDefaultTrigger() {
                        factoryCalls.incrementAndGet();
                        return triggerFactory.get();
                    }
                })
                .reduce(Integer::sum)
                .addSink(out::add);
    }

    /** Fires (keeping the window contents) on every {@code n}-th element of its bucket. */
    private static final class EveryNthFireTrigger implements WindowAssigner.Trigger<Integer> {
        private final int n;
        private long seen;

        private EveryNthFireTrigger(int n) {
            this.n = n;
        }

        @Override
        public WindowAssigner.TriggerResult onElement(Integer element, long timestamp, WindowAssigner.Window window) {
            if (++seen % n == 0) {
                return WindowAssigner.TriggerResult.FIRE;
            }
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
        }
    }

    /** FIRE_AND_PURGE on every element: each element emits immediately, nothing accumulates. */
    private static final class FireAndPurgeOnElementTrigger implements WindowAssigner.Trigger<Integer> {
        @Override
        public WindowAssigner.TriggerResult onElement(Integer element, long timestamp, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
        }

        @Override
        public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }
    }

    /** Defers the first close attempt of its bucket, then behaves like the stock trigger. */
    private static final class DeferOnceCloseTrigger implements WindowAssigner.Trigger<Integer> {
        private boolean deferred;

        @Override
        public WindowAssigner.TriggerResult onElement(Integer element, long timestamp, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
            if (!deferred) {
                deferred = true;
                return WindowAssigner.TriggerResult.CONTINUE;
            }
            return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
        }
    }

    /** PURGE at close: the window contents are dropped without ever emitting. */
    private static final class PurgeAtCloseTrigger implements WindowAssigner.Trigger<Integer> {
        @Override
        public WindowAssigner.TriggerResult onElement(Integer element, long timestamp, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.CONTINUE;
        }

        @Override
        public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
            return WindowAssigner.TriggerResult.PURGE;
        }
    }

    @Test
    void getDefaultTriggerIsConsultedAndOnElementFireEmitsPartialWhileWindowStillCloses() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = triggeredSumStream(() -> new EveryNthFireTrigger(2), factoryCalls, out);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            assertTrue(out.isEmpty(), "first element must not fire");
            runner.handle(msg(500L, 4));
            assertEquals(List.of(7), out,
                    "the trigger's onElement FIRE must emit the partial sum while the window keeps accumulating");

            runner.handle(msg(1_500L, 9));
            assertEquals(List.of(7, 7), out,
                    "after the partial fire the window must still close normally (state was kept)");
            assertTrue(factoryCalls.get() >= 1,
                    "getDefaultTrigger must be consulted — the former dead interface");
        } finally {
            runner.close();
        }
    }

    @Test
    void onElementFireAndPurgeClosesTheBucketSoLaterElementsStartFresh() throws Exception {
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = triggeredSumStream(FireAndPurgeOnElementTrigger::new, new AtomicInteger(), out);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            runner.handle(msg(500L, 4));
            assertEquals(List.of(3, 4), out,
                    "every element emits immediately and the purged bucket starts fresh");

            runner.handle(msg(1_500L, 9));
            // 9 is emitted by its OWN onElement fire-and-purge — record 9 lands in the
            // [1000,2000) bucket, whose element also triggers immediately. What must NOT
            // happen is a second emission of [0,1000): it left the due set when purged.
            assertEquals(List.of(3, 4, 9), out,
                    "each element emits once on arrival; a fire-and-purged bucket must not re-emit at close");
            assertTrue(dueBacking.isEmpty(),
                    "every purged bucket must be gone from the due set");
        } finally {
            runner.close();
        }
    }

    @Test
    void onEventTimeContinueDefersTheCloseFireToTheNextRecord() throws Exception {
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = triggeredSumStream(DeferOnceCloseTrigger::new, new AtomicInteger(), out);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            runner.handle(msg(500L, 4));
            runner.handle(msg(1_500L, 9));
            assertTrue(out.isEmpty(),
                    "the trigger's onEventTime CONTINUE must defer the close fire");

            runner.handle(msg(1_600L, 10));
            assertEquals(List.of(7), out,
                    "the deferred window must be retried on the next record and fire then");
        } finally {
            runner.close();
        }
    }

    @Test
    void onEventTimePurgeDropsTheWindowWithoutEmitting() throws Exception {
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = triggeredSumStream(PurgeAtCloseTrigger::new, new AtomicInteger(), out);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            runner.handle(msg(500L, 4));
            runner.handle(msg(1_500L, 9));
            assertTrue(out.isEmpty(), "PURGE must drop the window contents without emitting");
            assertFalse(backing.containsKey(MEMBER), "PURGE must clear the window state");
            // the purged window must leave the due set; only record 9's own [1000,2000)
            // window may remain (it closes in the future)
            assertEquals(Map.of(2000.0, "e:s:k" + D + "1000" + D + "2000"), dueBacking,
                    "the purged window must leave the due set");
        } finally {
            runner.close();
        }
    }

    @Test
    void stockEventTimeTriggerKeepsThePreWiringBehavior() throws Exception {
        List<Object> out = new CopyOnWriteArrayList<>();
        Object tail = triggeredSumStream(EventTimeTrigger::new, new AtomicInteger(), out);
        RedisPipelineRunner<Object> runner = runnerFor(tail);
        try {
            runner.handle(msg(500L, 3));
            runner.handle(msg(500L, 4));
            assertTrue(out.isEmpty(), "EventTimeTrigger must keep accumulating on element");
            runner.handle(msg(1_500L, 9));
            assertEquals(List.of(7), out,
                    "EventTimeTrigger fires exactly once at close with the full accumulation");
        } finally {
            runner.close();
        }
    }
}
