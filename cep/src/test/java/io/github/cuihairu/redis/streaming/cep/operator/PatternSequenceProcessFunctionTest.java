package io.github.cuihairu.redis.streaming.cep.operator;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.cep.EventSequence;
import io.github.cuihairu.redis.streaming.cep.Pattern;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PatternSequenceProcessFunctionTest {

    /** Tiny event type so patterns match on the type while timestamps ride along. */
    record E(String type, long ts) {
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
                                               List<Object[]> keyedEvents) throws Exception {
        List<EventSequence<E>> out = new ArrayList<>();
        KeyedProcessFunction.Collector<EventSequence<E>> collector = out::add;
        AtomicLong clock = new AtomicLong();
        KeyedProcessFunction.Context ctx = new KeyedProcessFunction.Context() {
            @Override
            public long currentProcessingTime() {
                return clock.incrementAndGet();
            }

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
        };
        for (Object[] ke : keyedEvents) {
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
        List<EventSequence<E>> out = feed(fn, events(
                new Object[]{"u1", new E("a", 0)},
                new Object[]{"u1", new E("b", 0)}));
        assertEquals(1, out.size());
        assertEquals(List.of(new E("a", 0), new E("b", 0)), out.get(0).getEventsCopy());
    }

    @Test
    void matchesPerKeyAndIsolatesKeys() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts);

        List<EventSequence<E>> out = feed(fn, events(
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

        List<EventSequence<E>> out = feed(fn, events(
                new Object[]{"u1", new E("a", 0)},
                new Object[]{"u1", new E("b", 1000)})); // past the 50ms deadline: partial expired
        assertTrue(out.isEmpty());
    }

    @Test
    void maxTrackedKeysEvictsIdleMatchersFirst() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts, 2);

        List<EventSequence<E>> out = feed(fn, events(
                new Object[]{"k1", new E("a", 1)},
                new Object[]{"k2", new E("a", 2)},   // both keys hold partials
                new Object[]{"k3", new E("a", 3)})); // exceeds cap, all active → oldest-touched (k1) evicted
        assertEquals(0, out.size());
        // k1's partial was evicted: its lone "b" cannot complete anything
        List<EventSequence<E>> more = feed(fn, events(
                new Object[]{"k1", new E("b", 4)}));
        assertTrue(more.isEmpty());
        assertEquals(2, fn.getTrackedKeyCount());
    }

    @Test
    void idleMatchersAreEvictedBeforeActiveOnes() throws Exception {
        PatternSequenceProcessFunction<String, E> fn =
                new PatternSequenceProcessFunction<>(abSequence(), E::ts, 2);

        feed(fn, events(
                new Object[]{"k1", new E("a", 1)},   // partial
                new Object[]{"k1", new E("b", 2)})); // completes → k1 idle (0 partials)
        feed(fn, events(new Object[]{"k2", new E("a", 3)})); // partial
        // cap is 2 with k1 idle: adding k3 evicts k1 (idle first), not k2 (holding a partial)
        feed(fn, events(new Object[]{"k3", new E("a", 4)}));
        assertEquals(2, fn.getTrackedKeyCount());

        // k2's partial survived the eviction: completing it still matches
        List<EventSequence<E>> out = feed(fn, events(
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
        feed(fn, events(
                new Object[]{"k1", new E("a", 1)},
                new Object[]{"k2", new E("a", 2)},
                new Object[]{"k3", new E("a", 3)},
                new Object[]{"k4", new E("a", 4)}));
        assertEquals(4, fn.getTrackedKeyCount());
    }
}
