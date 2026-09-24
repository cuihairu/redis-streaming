package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers edge branches of {@link InMemoryWindowedStream}: exhausted iterators of every
 * windowed aggregation, null element handling in windowed sum and {@code WindowKey.equals}
 * early-exit branches (unreachable through map lookups alone).
 */
class InMemoryWindowedStreamEdgeCaseTest {

    @Test
    void emptyWindowedOperatorsThrowNoSuchOnNext() {
        InMemoryKeyedStream<String, Integer> empty =
                new InMemoryKeyedStream<>(() -> Collections.<Integer>emptyIterator(), v -> "k");
        WindowAssigner<Integer> assigner = new FixedWindowAssigner<>(0, 10);

        assertEmptyIteratorThrows(empty.window(assigner).reduce((a, b) -> a + b));
        assertEmptyIteratorThrows(empty.window(assigner).aggregate(sumAgg()));
        assertEmptyIteratorThrows(empty.window(assigner).apply(noopWindowFn()));
        assertEmptyIteratorThrows(empty.window(assigner).sum(v -> v));
        assertEmptyIteratorThrows(empty.window(assigner).count());
    }

    @Test
    void windowedSumRejectsNonNumberAndNullElements() {
        WindowAssigner<String> assigner = new FixedWindowAssigner<>(0, 10);

        InMemoryKeyedStream<String, String> strings =
                new InMemoryKeyedStream<>(() -> List.of("s").iterator(), v -> "k");
        Exception e = assertThrows(Exception.class, () -> consume(strings.window(assigner).sum(v -> 1)));
        assertTrue(unwrapUoe(e).getMessage().contains("java.lang.String"));

        InMemoryKeyedStream<String, String> nulls =
                new InMemoryKeyedStream<>(() -> Collections.<String>singleton(null).iterator(), v -> "k");
        Exception e2 = assertThrows(Exception.class, () -> consume(nulls.window(assigner).sum(v -> 1)));
        assertTrue(unwrapUoe(e2).getMessage().contains("null"));
    }

    @Test
    void windowedAggregationsProduceResults() {
        InMemoryKeyedStream<String, Integer> keyed =
                new InMemoryKeyedStream<>(() -> List.of(1, 2, 3).iterator(), v -> "k");
        WindowAssigner<Integer> assigner = new FixedWindowAssigner<>(0, 100);

        List<Integer> reduced = new ArrayList<>();
        keyed.window(assigner).reduce((a, b) -> a + b).addSink(reduced::add);
        assertEquals(List.of(6), reduced);

        List<Long> counted = new ArrayList<>();
        keyed.window(assigner).count().addSink(counted::add);
        assertEquals(List.of(3L), counted);

        List<Long> aggregated = new ArrayList<>();
        keyed.window(assigner).aggregate(sumAgg()).addSink(aggregated::add);
        assertEquals(List.of(6L), aggregated);

        List<Integer> applied = new ArrayList<>();
        keyed.window(assigner).apply(noopWindowFn()).addSink(applied::add);
        assertEquals(List.of(6), applied);

        List<Integer> summed = new ArrayList<>();
        keyed.window(assigner).sum(v -> v).addSink(summed::add);
        assertEquals(List.of(6), summed);
    }

    @Test
    void windowKeyEqualsCoversEarlyExitBranches() throws Exception {
        Class<?> windowKeyClass = Class.forName(
                "io.github.cuihairu.redis.streaming.runtime.internal.InMemoryWindowedStream$WindowKey");
        Method of = windowKeyClass.getDeclaredMethod("of", Object.class, WindowAssigner.Window.class);
        of.setAccessible(true);
        Method equals = windowKeyClass.getDeclaredMethod("equals", Object.class);
        equals.setAccessible(true);

        Object a = of.invoke(null, "k", new SimpleWindow(0, 10));
        Object twin = of.invoke(null, "k", new SimpleWindow(0, 10));
        Object differentEnd = of.invoke(null, "k", new SimpleWindow(0, 11));
        Object differentKey = of.invoke(null, "x", new SimpleWindow(0, 10));

        assertEquals(Boolean.TRUE, equals.invoke(a, a));
        assertEquals(Boolean.FALSE, equals.invoke(a, (Object) null));
        assertEquals(Boolean.FALSE, equals.invoke(a, "not-a-window-key"));
        assertEquals(Boolean.TRUE, equals.invoke(a, twin));
        assertEquals(Boolean.FALSE, equals.invoke(a, differentEnd));
        assertEquals(Boolean.FALSE, equals.invoke(a, differentKey));
    }

    private static io.github.cuihairu.redis.streaming.api.stream.AggregateFunction<Integer, Long> sumAgg() {
        return new io.github.cuihairu.redis.streaming.api.stream.AggregateFunction<>() {
            @Override
            public Accumulator<Integer> createAccumulator() {
                return new SumAccumulator();
            }

            @Override
            public Accumulator<Integer> add(Integer value, Accumulator<Integer> accumulator) {
                SumAccumulator acc = (SumAccumulator) accumulator;
                acc.sum += value;
                return acc;
            }

            @Override
            public Long getResult(Accumulator<Integer> accumulator) {
                return ((SumAccumulator) accumulator).sum;
            }

            @Override
            public Accumulator<Integer> merge(Accumulator<Integer> a, Accumulator<Integer> b) {
                SumAccumulator merged = new SumAccumulator();
                merged.sum = ((SumAccumulator) a).sum + ((SumAccumulator) b).sum;
                return merged;
            }
        };
    }

    private static final class SumAccumulator implements io.github.cuihairu.redis.streaming.api.stream.AggregateFunction.Accumulator<Integer> {
        private long sum;
    }

    private static WindowFunction<String, Integer, Integer> noopWindowFn() {
        return (key, window, elements, out) -> {
            int sum = 0;
            for (Integer v : elements) {
                sum += v;
            }
            out.collect(sum);
        };
    }

    private static void assertEmptyIteratorThrows(DataStream<?> stream) {
        Iterator<?> it = ((InMemoryDataStream<?>) stream).iterator();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    private static void consume(DataStream<?> stream) {
        ((InMemoryDataStream<?>) stream).iterator().forEachRemaining(v -> {
        });
    }

    private static UnsupportedOperationException unwrapUoe(Exception e) {
        Throwable current = e;
        while (current != null && !(current instanceof UnsupportedOperationException)) {
            current = current.getCause();
        }
        assertInstanceOf(UnsupportedOperationException.class, current);
        return (UnsupportedOperationException) current;
    }

    private static final class FixedWindowAssigner<T> implements WindowAssigner<T> {
        private final long start;
        private final long end;

        private FixedWindowAssigner(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public Iterable<Window> assignWindows(T element, long timestamp) {
            return List.of(new SimpleWindow(start, end));
        }

        @Override
        public Trigger<T> getDefaultTrigger() {
            return new Trigger<>() {
                @Override
                public TriggerResult onElement(T element, long timestamp, Window window) {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onProcessingTime(long time, Window window) {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onEventTime(long time, Window window) {
                    return TriggerResult.CONTINUE;
                }
            };
        }
    }

    private static final class SimpleWindow implements WindowAssigner.Window {
        private final long start;
        private final long end;

        private SimpleWindow(long start, long end) {
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
}
