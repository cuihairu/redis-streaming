package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryWindowedStream
 */
class InMemoryWindowedStreamTest {

    // Test helper: simple tumbling window assigner
    static class TumblingWindowAssigner<T> implements WindowAssigner<T> {
        private final long size;

        TumblingWindowAssigner(long size) {
            this.size = size;
        }

        @Override
        public Iterable<Window> assignWindows(T element, long timestamp) {
            long windowStart = (timestamp / size) * size;
            long windowEnd = windowStart + size;
            return List.of(new SimpleWindow(windowStart, windowEnd));
        }

        @Override
        public Trigger<T> getDefaultTrigger() {
            return new Trigger<>() {
                @Override
                public TriggerResult onElement(T element, long timestamp, Window window) {
                    return TriggerResult.FIRE;
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

    static class SimpleWindow implements WindowAssigner.Window {
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

    @Test
    void testReduceWithSingleWindow() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key1", 2, 150L),
                new KeyedRecord<>("key1", 3, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce((a, b) -> a + b).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(6, results.get(0));
    }

    @Test
    void testReduceWithMultipleWindows() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 50L),
                new KeyedRecord<>("key1", 2, 120L),
                new KeyedRecord<>("key1", 3, 250L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce((a, b) -> a + b).addSink(results::add);

        assertEquals(3, results.size());
        assertTrue(results.contains(1));
        assertTrue(results.contains(2));
        assertTrue(results.contains(3));
    }

    @Test
    void testReduceWithMultipleKeys() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key2", 10, 100L),
                new KeyedRecord<>("key1", 2, 150L),
                new KeyedRecord<>("key2", 20, 150L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce((a, b) -> a + b).addSink(results::add);

        assertEquals(2, results.size());
        assertTrue(results.contains(3));  // key1: 1 + 2
        assertTrue(results.contains(30)); // key2: 10 + 20
    }

    @Test
    void testReduceWithMultiplication() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 2, 100L),
                new KeyedRecord<>("key1", 3, 150L),
                new KeyedRecord<>("key1", 4, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce((a, b) -> a * b).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(24, results.get(0));
    }

    @Test
    void testReduceWithException() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key1", 0, 150L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                windowed.reduce((a, b) -> a / b).addSink(v -> {})
        );

        assertTrue(ex.getMessage().contains("Window reduce function failed"));
        assertTrue(ex.getCause() instanceof ArithmeticException);
    }

    @Test
    void testReduceWithNullReducerThrowsNPE() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        assertThrows(NullPointerException.class, () -> windowed.reduce(null));
    }

    @Test
    void testAggregateWithSum() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key1", 2, 150L),
                new KeyedRecord<>("key1", 3, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        // Custom accumulator for sum
        class SumAccumulator implements AggregateFunction.Accumulator<Integer> {
            int sum = 0;
        }

        AggregateFunction<Integer, Integer> sumFn = new AggregateFunction<>() {
            @Override
            public Accumulator<Integer> createAccumulator() {
                return new SumAccumulator();
            }

            @Override
            public Accumulator<Integer> add(Integer value, Accumulator<Integer> accumulator) {
                ((SumAccumulator) accumulator).sum += value;
                return accumulator;
            }

            @Override
            public Integer getResult(Accumulator<Integer> accumulator) {
                return ((SumAccumulator) accumulator).sum;
            }

            @Override
            public Accumulator<Integer> merge(Accumulator<Integer> a, Accumulator<Integer> b) {
                SumAccumulator merged = new SumAccumulator();
                merged.sum = ((SumAccumulator) a).sum + ((SumAccumulator) b).sum;
                return merged;
            }
        };

        List<Integer> results = new ArrayList<>();
        windowed.aggregate(sumFn).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(6, results.get(0));
    }

    @Test
    void testAggregateWithAverage() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 10, 100L),
                new KeyedRecord<>("key1", 20, 150L),
                new KeyedRecord<>("key1", 30, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        // Custom accumulator for average
        class AvgAccumulator implements AggregateFunction.Accumulator<Integer> {
            int sum = 0;
            int count = 0;
        }

        AggregateFunction<Integer, Double> avgFn = new AggregateFunction<>() {
            @Override
            public Accumulator<Integer> createAccumulator() {
                return new AvgAccumulator();
            }

            @Override
            public Accumulator<Integer> add(Integer value, Accumulator<Integer> accumulator) {
                AvgAccumulator acc = (AvgAccumulator) accumulator;
                acc.sum += value;
                acc.count++;
                return accumulator;
            }

            @Override
            public Double getResult(Accumulator<Integer> accumulator) {
                AvgAccumulator acc = (AvgAccumulator) accumulator;
                return acc.count == 0 ? 0.0 : (double) acc.sum / acc.count;
            }

            @Override
            public Accumulator<Integer> merge(Accumulator<Integer> a, Accumulator<Integer> b) {
                AvgAccumulator merged = new AvgAccumulator();
                merged.sum = ((AvgAccumulator) a).sum + ((AvgAccumulator) b).sum;
                merged.count = ((AvgAccumulator) a).count + ((AvgAccumulator) b).count;
                return merged;
            }
        };

        List<Double> results = new ArrayList<>();
        windowed.aggregate(avgFn).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(20.0, results.get(0), 0.001);
    }

    @Test
    void testAggregateWithNullFunctionThrowsNPE() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        assertThrows(NullPointerException.class, () -> windowed.aggregate(null));
    }

    @Test
    void testApplyWithSingleWindow() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key1", 2, 150L),
                new KeyedRecord<>("key1", 3, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        WindowFunction<String, Integer, String> fn = (key, window, elements, out) -> {
            List<Integer> values = new ArrayList<>();
            for (Integer e : elements) {
                values.add(e);
            }
            out.collect(key + ":" + values);
        };

        List<String> results = new ArrayList<>();
        windowed.apply(fn).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals("key1:[1, 2, 3]", results.get(0));
    }

    @Test
    void testApplyEmittingMultipleValues() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key1", 2, 150L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        WindowFunction<String, Integer, Integer> fn = (key, window, elements, out) -> {
            for (Integer e : elements) {
                out.collect(e);
                out.collect(e * 2);
            }
        };

        List<Integer> results = new ArrayList<>();
        windowed.apply(fn).addSink(results::add);

        assertEquals(4, results.size());
        assertTrue(results.contains(1));
        assertTrue(results.contains(2));
        assertTrue(results.contains(2));  // 1 * 2
        assertTrue(results.contains(4));  // 2 * 2
    }

    @Test
    void testApplyWithWindowBounds() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 50L),
                new KeyedRecord<>("key1", 2, 120L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        WindowFunction<String, Integer, String> fn = (key, window, elements, out) -> {
            out.collect("[" + window.getStart() + "-" + window.getEnd() + "]:" + elements);
        };

        List<String> results = new ArrayList<>();
        windowed.apply(fn).addSink(results::add);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.startsWith("[0-100]:")));
        assertTrue(results.stream().anyMatch(r -> r.startsWith("[100-200]:")));
    }

    @Test
    void testApplyWithException() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        WindowFunction<String, Integer, String> fn = (key, window, elements, out) -> {
            throw new IllegalArgumentException("Test exception");
        };

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                windowed.apply(fn).addSink(v -> {})
        );

        assertTrue(ex.getMessage().contains("Window function failed"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void testApplyWithNullFunctionThrowsNPE() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        assertThrows(NullPointerException.class, () -> windowed.apply(null));
    }

    @Test
    void testCountWithSingleWindow() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key1", 2, 150L),
                new KeyedRecord<>("key1", 3, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Long> results = new ArrayList<>();
        windowed.count().addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(3L, results.get(0));
    }

    @Test
    void testCountWithMultipleWindows() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 50L),
                new KeyedRecord<>("key1", 2, 120L),
                new KeyedRecord<>("key1", 3, 250L),
                new KeyedRecord<>("key1", 4, 350L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Long> results = new ArrayList<>();
        windowed.count().addSink(results::add);

        assertEquals(4, results.size());
        assertTrue(results.contains(1L));
        assertTrue(results.contains(1L));
        assertTrue(results.contains(1L));
        assertTrue(results.contains(1L));
    }

    @Test
    void testCountWithMultipleKeys() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L),
                new KeyedRecord<>("key2", 10, 100L),
                new KeyedRecord<>("key2", 20, 150L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Long> results = new ArrayList<>();
        windowed.count().addSink(results::add);

        assertEquals(2, results.size());
        assertTrue(results.contains(1L));
        assertTrue(results.contains(2L));
    }

    @Test
    void testSumWithIntegerValues() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 10, 100L),
                new KeyedRecord<>("key1", 20, 150L),
                new KeyedRecord<>("key1", 30, 180L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.sum(Integer::intValue).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(60, results.get(0));
    }

    @Test
    void testSumWithLongValues() {
        List<KeyedRecord<String, Long>> records = List.of(
                new KeyedRecord<>("key1", 10L, 100L),
                new KeyedRecord<>("key1", 20L, 150L),
                new KeyedRecord<>("key1", 30L, 180L)
        );

        InMemoryWindowedStream<String, Long> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Long> results = new ArrayList<>();
        windowed.sum(Long::longValue).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(60L, results.get(0));
    }

    @Test
    void testSumWithDoubleValues() {
        List<KeyedRecord<String, Double>> records = List.of(
                new KeyedRecord<>("key1", 10.5, 100L),
                new KeyedRecord<>("key1", 20.3, 150L),
                new KeyedRecord<>("key1", 30.2, 180L)
        );

        InMemoryWindowedStream<String, Double> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Double> results = new ArrayList<>();
        windowed.sum(Double::doubleValue).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(61.0, results.get(0), 0.001);
    }

    @Test
    void testSumWithNullFieldSelectorThrowsNPE() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 1, 100L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        assertThrows(NullPointerException.class, () -> windowed.sum(null));
    }

    @Test
    void testSumWithNonNumberElementThrowsException() {
        List<KeyedRecord<String, String>> records = List.of(
                new KeyedRecord<>("key1", "not-a-number", 100L)
        );

        InMemoryWindowedStream<String, String> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () ->
                windowed.sum(v -> null).addSink(v -> {})
        );

        assertTrue(ex.getMessage().contains("only supports Number elements"));
    }

    @Test
    void testEmptyInput() {
        List<KeyedRecord<String, Integer>> records = List.of();

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce((a, b) -> a + b).addSink(results::add);

        assertTrue(results.isEmpty());
    }

    @Test
    void testReduceWithMaxOperation() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 5, 100L),
                new KeyedRecord<>("key1", 3, 150L),
                new KeyedRecord<>("key1", 8, 180L),
                new KeyedRecord<>("key1", 1, 190L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce(Math::max).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(8, results.get(0));
    }

    @Test
    void testReduceWithMinOperation() {
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("key1", 5, 100L),
                new KeyedRecord<>("key1", 3, 150L),
                new KeyedRecord<>("key1", 8, 180L),
                new KeyedRecord<>("key1", 1, 190L)
        );

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        List<Integer> results = new ArrayList<>();
        windowed.reduce(Math::min).addSink(results::add);

        assertEquals(1, results.size());
        assertEquals(1, results.get(0));
    }

    @Test
    void testApplyWithEmptyElements() {
        List<KeyedRecord<String, Integer>> records = List.of();

        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(
                records::iterator,
                new TumblingWindowAssigner<>(100)
        );

        WindowFunction<String, Integer, Integer> fn = (key, window, elements, out) -> {
            int count = 0;
            for (@SuppressWarnings("unused") Integer e : elements) {
                count++;
            }
            out.collect(count);
        };

        List<Integer> results = new ArrayList<>();
        windowed.apply(fn).addSink(results::add);

        assertTrue(results.isEmpty());
    }

    @Test
    void testConstructorWithNullIteratorSupplierThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
                new InMemoryWindowedStream<>(null, new TumblingWindowAssigner<>(100))
        );
    }

    @Test
    void testConstructorWithNullWindowAssignerThrowsNPE() {
        assertThrows(NullPointerException.class, () ->
                new InMemoryWindowedStream<>(List.<KeyedRecord<String, Integer>>of()::iterator, null)
        );
    }
}
