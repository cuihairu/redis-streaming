package io.github.cuihairu.redis.streaming.cep.operator;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.cep.EventSequence;
import io.github.cuihairu.redis.streaming.cep.Pattern;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PatternSequenceProcessFunctionTest {

    /** Tiny event type so patterns match on the type while timestamps ride along. */
    record E(String type, long ts) {
    }

    /** Minimal keyed value state: one JSON document per (state name, current key). */
    private static final class StatefulContext implements KeyedProcessFunction.Context {
        final Map<String, Map<String, String>> store = new HashMap<>();
        String currentKey = "k";

        @Override
        public long currentProcessingTime() {
            return clock.incrementAndGet();
        }

        final AtomicLong clock = new AtomicLong();

        @Override
        public long currentWatermark() {
            return 0;
        }

        @Override
        public void registerProcessingTimeTimer(long time) {
        }

        @Override
        public void registerEventTimeTimer(long time) {
        }

        @Override
        public <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor) {
            return new ValueState<>() {
                @Override
                @SuppressWarnings("unchecked")
                public S value() {
                    Map<String, String> byKey = store.get(stateDescriptor.getName());
                    String json = byKey == null ? null : byKey.get(currentKey);
                    return json == null ? stateDescriptor.getDefaultValue() : (S) json;
                }

                @Override
                public void update(S value) {
                    store.computeIfAbsent(stateDescriptor.getName(), k -> new HashMap<>())
                            .put(currentKey, (String) value);
                }

                @Override
                public void clear() {
                    Map<String, String> byKey = store.get(stateDescriptor.getName());
                    if (byKey != null) {
                        byKey.remove(currentKey);
                    }
                }
            };
        }
    }

    private static Pattern<E> type(String t) {
        return Pattern.of(e -> e.type().equals(t));
    }

    /** Two-step strict sequence: "a" then "b". */
    private static PatternSequence<E> abSequence() {
        return PatternSequence.<E>begin("first", type("a"))
                .next("second", type("b"));
    }

    private static List<EventSequence<E>> feed(PatternSequenceProcessFunction<String, E> fn,
                                               StatefulContext ctx,
                                               List<Object[]> keyedEvents) throws Exception {
        List<EventSequence<E>> out = new ArrayList<>();
        KeyedProcessFunction.Collector<EventSequence<E>> collector = out::add;
        for (Object[] ke : keyedEvents) {
            ctx.currentKey = (String) ke[0];
            fn.processElement((String) ke[0], (E) ke[1], ctx, collector);
        }
        return out;
    }

    private static List<Object[]> events(Object[]... keyed) {
        return Arrays.asList(keyed);
    }

    @Test
    void nullExtractorFallsBackToCurrentTimeAndStillMatches() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), null);
        List<EventSequence<E>> out = feed(fn, new StatefulContext(), events(
                new Object[]{"u1", new E("a", 0)},
                new Object[]{"u1", new E("b", 0)}));
        assertEquals(1, out.size());
        assertEquals(List.of(new E("a", 0), new E("b", 0)), out.get(0).getEventsCopy());
    }

    @Test
    void matchesPerKeyAndIsolatesKeys() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts);

        List<EventSequence<E>> out = feed(fn, new StatefulContext(), events(
                new Object[]{"u1", new E("a", 100)},
                new Object[]{"u2", new E("a", 110)},   // different key: must not complete u1's partial
                new Object[]{"u1", new E("b", 120)}));
        assertEquals(1, out.size());
        assertEquals(List.of(new E("a", 100), new E("b", 120)), out.get(0).getEventsCopy());
        assertEquals(20, out.get(0).getDuration());
        assertEquals(2, out.get(0).size());
    }

    @Test
    void withinDeadlineDropsStalePartials() throws Exception {
        PatternSequence<E> seq = PatternSequence.<E>begin("first", type("a"))
                .next("second", type("b"))
                .within(Duration.ofMillis(50));
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(seq, E::ts);

        List<EventSequence<E>> out = feed(fn, new StatefulContext(), events(
                new Object[]{"u1", new E("a", 0)},
                new Object[]{"u1", new E("b", 1000)})); // past the 50ms deadline: partial expired
        assertTrue(out.isEmpty());
    }

    @Test
    void maxTrackedKeysEvictsIdleMatchersFirst() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts, 2);
        StatefulContext ctx = new StatefulContext();

        List<EventSequence<E>> out = feed(fn, ctx, events(
                new Object[]{"k1", new E("a", 1)},
                new Object[]{"k2", new E("a", 2)},   // both keys hold partials
                new Object[]{"k3", new E("a", 3)})); // exceeds cap, all active → oldest-touched (k1) evicted
        assertEquals(0, out.size());
        // k1's partial was evicted: its lone "b" cannot complete anything (and the evicted
        // key's checkpointed partial must not resurrect within the same instance)
        List<EventSequence<E>> more = feed(fn, ctx, events(
                new Object[]{"k1", new E("b", 4)}));
        assertTrue(more.isEmpty());
        assertEquals(2, fn.getTrackedKeyCount());
    }

    @Test
    void idleMatchersAreEvictedBeforeActiveOnes() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts, 2);
        StatefulContext ctx = new StatefulContext();

        feed(fn, ctx, events(
                new Object[]{"k1", new E("a", 1)},   // partial
                new Object[]{"k1", new E("b", 2)})); // completes → k1 idle (0 partials)
        feed(fn, ctx, events(new Object[]{"k2", new E("a", 3)})); // partial
        // cap is 2 with k1 idle: adding k3 evicts k1 (idle first), not k2 (holding a partial)
        feed(fn, ctx, events(new Object[]{"k3", new E("a", 4)}));
        assertEquals(2, fn.getTrackedKeyCount());

        // k2's partial survived the eviction: completing it still matches
        List<EventSequence<E>> out = feed(fn, ctx, events(
                new Object[]{"k2", new E("b", 5)}));
        assertEquals(1, out.size());
        assertEquals(List.of(new E("a", 3), new E("b", 5)), out.get(0).getEventsCopy());
    }

    @Test
    void negativeMaxTrackedKeysRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PatternSequenceProcessFunction<>(abSequence(), E::ts, -1));
    }

    @Test
    void zeroCapMeansUnboundedTracking() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts, 0);
        StatefulContext ctx = new StatefulContext();
        feed(fn, ctx, events(
                new Object[]{"k1", new E("a", 1)},
                new Object[]{"k2", new E("a", 2)},
                new Object[]{"k3", new E("a", 3)},
                new Object[]{"k4", new E("a", 4)}));
        assertEquals(4, fn.getTrackedKeyCount());
    }

    @Test
    void partialsSurviveAFreshFunctionInstance() throws Exception {
        // One store = one engine's keyed state; a restart constructs a NEW function
        // instance that must resume k1's in-progress partial from state.
        StatefulContext ctx = new StatefulContext();
        PatternSequenceProcessFunction<String, E> first =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts);
        assertTrue(feed(first, ctx, events(new Object[]{"k1", new E("a", 100)})).isEmpty());

        PatternSequenceProcessFunction<String, E> restarted =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts);
        List<EventSequence<E>> out = feed(restarted, ctx, events(new Object[]{"k1", new E("b", 120)}));
        assertEquals(1, out.size());
        assertEquals(List.of(new E("a", 100), new E("b", 120)), out.get(0).getEventsCopy());
    }

    @Test
    void completedSequencesClearKeyedState() throws Exception {
        StatefulContext ctx = new StatefulContext();
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts);

        feed(fn, ctx, events(
                new Object[]{"k1", new E("a", 1)},
                new Object[]{"k1", new E("b", 2)}));
        Map<String, String> cepState = ctx.store.get("__operator:cep:partials");
        assertTrue(cepState == null || !cepState.containsKey("k1"),
                "no in-progress partials left: the key's document must be cleared");
    }
}
