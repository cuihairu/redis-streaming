package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import io.github.cuihairu.redis.streaming.window.triggers.CountTrigger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trigger-protocol tests for {@link InMemoryWindowedStream}: the engine must actually invoke
 * {@link WindowAssigner#getDefaultTrigger()} per (key, window) bucket and honor
 * FIRE / FIRE_AND_PURGE / PURGE / CONTINUE results, plus the end-of-input
 * onEventTime + flush semantics.
 */
class InMemoryWindowedStreamTriggerTest {

    /** Tumbling assigner (size 100) whose default trigger is supplied by the test. */
    static final class TriggeredWindowAssigner<T> implements WindowAssigner<T> {
        private final java.util.function.Supplier<WindowAssigner.Trigger<T>> triggerFactory;

        TriggeredWindowAssigner(java.util.function.Supplier<WindowAssigner.Trigger<T>> triggerFactory) {
            this.triggerFactory = triggerFactory;
        }

        @Override
        public Iterable<Window> assignWindows(T element, long timestamp) {
            long start = (timestamp / 100) * 100;
            return List.of(new SimpleWindow(start, start + 100));
        }

        @Override
        public WindowAssigner.Trigger<T> getDefaultTrigger() {
            return triggerFactory.get();
        }
    }

    static final class SimpleWindow implements WindowAssigner.Window {
        private final long start;
        private final long end;

        SimpleWindow(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public long getStart() {
            return start;
        }

        @Override
        public long getEnd() {
            return end;
        }
    }

    /** Count-based trigger: fires (FIRE or FIRE_AND_PURGE) every {@code n} elements, per instance. */
    static final class EveryNTrigger<T> implements WindowAssigner.Trigger<T> {
        private final int n;
        private final boolean purge;
        private int count;

        EveryNTrigger(int n, boolean purge) {
            this.n = n;
            this.purge = purge;
        }

        @Override
        public WindowAssigner.TriggerResult onElement(T element, long timestamp, WindowAssigner.Window window) {
            if (++count >= n) {
                count = 0;
                return purge ? WindowAssigner.TriggerResult.FIRE_AND_PURGE
                        : WindowAssigner.TriggerResult.FIRE;
            }
            return WindowAssigner.TriggerResult.CONTINUE;
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

    private static InMemoryWindowedStream<String, Integer> windowed(
            java.util.function.Supplier<WindowAssigner.Trigger<Integer>> triggerFactory,
            List<KeyedRecord<String, Integer>> records) {
        return new InMemoryWindowedStream<>(records::iterator, new TriggeredWindowAssigner<>(triggerFactory));
    }

    @Test
    void fireEmitsPartialResultAndKeepsAccumulating() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L),
                new KeyedRecord<>("k", 2, 20L),
                new KeyedRecord<>("k", 3, 30L)
        );

        List<Integer> results = new ArrayList<>();
        windowed(() -> new WindowAssigner.Trigger<Integer>() {
            @Override
            public WindowAssigner.TriggerResult onElement(Integer e, long ts, Window w) {
                return WindowAssigner.TriggerResult.FIRE;
            }

            @Override
            public WindowAssigner.TriggerResult onProcessingTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }

            @Override
            public WindowAssigner.TriggerResult onEventTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }
        }, records).reduce(Integer::sum).addSink(results::add);

        // Partial fires 1, 3, 6; end-of-input flush re-emits the retained contents (6).
        assertEquals(List.of(1, 3, 6, 6), results);
    }

    @Test
    void fireAndPurgeEmitsAndClearsSoEndFlushEmitsNothing() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L),
                new KeyedRecord<>("k", 2, 20L),
                new KeyedRecord<>("k", 3, 30L),
                new KeyedRecord<>("k", 4, 40L)
        );

        List<Integer> results = new ArrayList<>();
        windowed(() -> new EveryNTrigger<Integer>(2, true), records)
                .reduce(Integer::sum).addSink(results::add);

        // Fires after elements 2 and 4; both purge, so the final flush finds empty buckets.
        assertEquals(List.of(3, 7), results);
    }

    @Test
    void purgeDropsContentsWithoutEmitting() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L),
                new KeyedRecord<>("k", 2, 20L)
        );

        List<Integer> results = new ArrayList<>();
        windowed(() -> new WindowAssigner.Trigger<Integer>() {
            @Override
            public WindowAssigner.TriggerResult onElement(Integer e, long ts, Window w) {
                return WindowAssigner.TriggerResult.PURGE;
            }

            @Override
            public WindowAssigner.TriggerResult onProcessingTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }

            @Override
            public WindowAssigner.TriggerResult onEventTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }
        }, records).reduce(Integer::sum).addSink(results::add);

        assertTrue(results.isEmpty());
    }

    @Test
    void perWindowTriggerInstancesAreIsolated() {
        // Two interleaved windows; if trigger instances were shared across buckets the count
        // would reach 2 after one element in each window and fire at the wrong moment.
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 50L),    // window [0,100): element 1
                new KeyedRecord<>("k", 10, 150L),  // window [100,200): element 1
                new KeyedRecord<>("k", 2, 80L),    // window [0,100): element 2 -> per-window FIRE
                new KeyedRecord<>("k", 20, 180L)   // window [100,200): element 2 -> per-window FIRE
        );

        List<Integer> results = new ArrayList<>();
        windowed(() -> new CountTrigger<Integer>(2), records)
                .reduce(Integer::sum).addSink(results::add);

        // CountTrigger returns FIRE (contents kept): two partial fires + two final flushes.
        assertEquals(List.of(3, 30, 3, 30), results);
    }

    @Test
    void windowModuleTumblingWindowWithDefaultEventTimeTriggerKeepsBatchSemantics() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L),
                new KeyedRecord<>("k", 2, 20L),
                new KeyedRecord<>("k", 3, 30L)
        );

        List<Integer> results = new ArrayList<>();
        new InMemoryWindowedStream<>(records::iterator, TumblingWindow.<Integer>ofMillis(100))
                .reduce(Integer::sum).addSink(results::add);

        // EventTimeTrigger never fires on element; single emission from the end-of-input flush.
        assertEquals(List.of(6), results);
    }

    @Test
    void countFiresPartiallyWithEveryNTrigger() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L),
                new KeyedRecord<>("k", 2, 20L),
                new KeyedRecord<>("k", 3, 30L),
                new KeyedRecord<>("k", 4, 40L)
        );

        List<Long> results = new ArrayList<>();
        windowed(() -> new EveryNTrigger<Integer>(3, false), records)
                .count().addSink(results::add);

        // Partial count of 3 after element 3 (FIRE keeps contents), then final flush count of 4.
        assertEquals(List.of(3L, 4L), results);
    }

    @Test
    void endOfInputConsultsOnEventTimeWithWindowEnd() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L),
                new KeyedRecord<>("k", 2, 120L)
        );

        List<String> calls = new ArrayList<>();
        List<Integer> results = new ArrayList<>();
        windowed(() -> new WindowAssigner.Trigger<Integer>() {
            @Override
            public WindowAssigner.TriggerResult onElement(Integer e, long ts, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }

            @Override
            public WindowAssigner.TriggerResult onProcessingTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }

            @Override
            public WindowAssigner.TriggerResult onEventTime(long t, Window w) {
                calls.add(t + "@" + w.getStart() + "-" + w.getEnd());
                return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
            }
        }, records).reduce(Integer::sum).addSink(results::add);

        assertEquals(List.of(1, 2), results);
        // Each bucket's trigger sees exactly one onEventTime at its window end.
        assertEquals(List.of("100@0-100", "200@100-200"), calls);
    }

    @Test
    void triggerFailurePropagates() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 1, 10L)
        );

        AtomicInteger onElementCalls = new AtomicInteger();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                windowed(() -> new WindowAssigner.Trigger<Integer>() {
                    @Override
                    public WindowAssigner.TriggerResult onElement(Integer e, long ts, Window w) {
                        if (onElementCalls.incrementAndGet() > 0) {
                            throw new IllegalStateException("boom");
                        }
                        return WindowAssigner.TriggerResult.CONTINUE;
                    }

                    @Override
                    public WindowAssigner.TriggerResult onProcessingTime(long t, Window w) {
                        return WindowAssigner.TriggerResult.CONTINUE;
                    }

                    @Override
                    public WindowAssigner.TriggerResult onEventTime(long t, Window w) {
                        return WindowAssigner.TriggerResult.CONTINUE;
                    }
                }, records).reduce(Integer::sum).addSink(v -> {
                }));

        assertEquals("boom", ex.getMessage());
    }

    @Test
    void emptyInputNeverConsultsTriggers() {
        List<KeyedRecord<String, Integer>> records = List.of();

        List<Integer> results = new ArrayList<>();
        windowed(() -> {
            throw new AssertionError("getDefaultTrigger must not be called without data");
        }, records).reduce(Integer::sum).addSink(results::add);

        assertTrue(results.isEmpty());
    }
}
