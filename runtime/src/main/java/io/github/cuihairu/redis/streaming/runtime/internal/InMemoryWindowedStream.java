package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class InMemoryWindowedStream<K, T> implements WindowedStream<K, T> {

    private final Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier;
    private final WindowAssigner<T> windowAssigner;

    InMemoryWindowedStream(Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier,
                           WindowAssigner<T> windowAssigner) {
        this.keyedIteratorSupplier = Objects.requireNonNull(keyedIteratorSupplier, "keyedIteratorSupplier");
        this.windowAssigner = Objects.requireNonNull(windowAssigner, "windowAssigner");
    }

    @Override
    public DataStream<T> reduce(ReduceFunction<T> reducer) {
        Objects.requireNonNull(reducer, "reducer");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private Iterator<InMemoryRecord<T>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = reduceAll(reducer).iterator();
                }
                return out.hasNext();
            }

            @Override
            public InMemoryRecord<T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return out.next();
            }
        });
    }

    @Override
    public <R> DataStream<R> aggregate(AggregateFunction<T, R> aggregateFunction) {
        Objects.requireNonNull(aggregateFunction, "aggregateFunction");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private Iterator<InMemoryRecord<R>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = aggregateAll(aggregateFunction).iterator();
                }
                return out.hasNext();
            }

            @Override
            public InMemoryRecord<R> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return out.next();
            }
        });
    }

    @Override
    public <R> DataStream<R> apply(WindowFunction<K, T, R> windowFunction) {
        Objects.requireNonNull(windowFunction, "windowFunction");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private Iterator<InMemoryRecord<R>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = applyAll(windowFunction).iterator();
                }
                return out.hasNext();
            }

            @Override
            public InMemoryRecord<R> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return out.next();
            }
        });
    }

    @Override
    public DataStream<T> sum(Function<T, ? extends Number> fieldSelector) {
        Objects.requireNonNull(fieldSelector, "fieldSelector");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private Iterator<InMemoryRecord<T>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = sumAll(fieldSelector).iterator();
                }
                return out.hasNext();
            }

            @Override
            public InMemoryRecord<T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return out.next();
            }
        });
    }

    @Override
    public DataStream<Long> count() {
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private Iterator<InMemoryRecord<Long>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = countAll().iterator();
                }
                return out.hasNext();
            }

            @Override
            public InMemoryRecord<Long> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return out.next();
            }
        });
    }

    private List<InMemoryRecord<T>> reduceAll(ReduceFunction<T> reducer) {
        Map<WindowKey<K>, Acc<T>> acc = new LinkedHashMap<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            for (WindowAssigner.Window window : windowAssigner.assignWindows(record.value(), record.timestamp())) {
                WindowKey<K> key = WindowKey.of(record.key(), window);
                Acc<T> a = acc.get(key);
                if (a == null) {
                    acc.put(key, new Acc<>(record.value(), record.timestamp()));
                    continue;
                }
                try {
                    a.value = reducer.reduce(a.value, record.value());
                    a.lastTimestamp = record.timestamp();
                } catch (Exception e) {
                    throw new RuntimeException("Window reduce function failed", e);
                }
            }
        }
        List<InMemoryRecord<T>> out = new ArrayList<>(acc.size());
        for (Acc<T> v : acc.values()) {
            out.add(new InMemoryRecord<>(v.value, v.lastTimestamp));
        }
        return out;
    }

    private <R> List<InMemoryRecord<R>> aggregateAll(AggregateFunction<T, R> fn) {
        Map<WindowKey<K>, AggregateAcc<T>> acc = new LinkedHashMap<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            for (WindowAssigner.Window window : windowAssigner.assignWindows(record.value(), record.timestamp())) {
                WindowKey<K> key = WindowKey.of(record.key(), window);
                AggregateAcc<T> a = acc.get(key);
                if (a == null) {
                    a = new AggregateAcc<>(fn.createAccumulator(), record.timestamp());
                    acc.put(key, a);
                }
                a.accumulator = fn.add(record.value(), a.accumulator);
                a.lastTimestamp = record.timestamp();
            }
        }
        List<InMemoryRecord<R>> out = new ArrayList<>(acc.size());
        for (AggregateAcc<T> a : acc.values()) {
            out.add(new InMemoryRecord<>(fn.getResult(a.accumulator), a.lastTimestamp));
        }
        return out;
    }

    private <R> List<InMemoryRecord<R>> applyAll(WindowFunction<K, T, R> fn) {
        Map<WindowKey<K>, WindowValues<T>> values = new LinkedHashMap<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            for (WindowAssigner.Window window : windowAssigner.assignWindows(record.value(), record.timestamp())) {
                WindowKey<K> key = WindowKey.of(record.key(), window);
                WindowValues<T> w = values.get(key);
                if (w == null) {
                    w = new WindowValues<>(new ArrayList<>(), record.timestamp());
                    values.put(key, w);
                }
                w.elements.add(record.value());
                w.lastTimestamp = record.timestamp();
            }
        }

        List<InMemoryRecord<R>> out = new ArrayList<>();
        for (Map.Entry<WindowKey<K>, WindowValues<T>> entry : values.entrySet()) {
            WindowKey<K> key = entry.getKey();
            WindowValues<T> w = entry.getValue();
            ArrayDeque<R> buffer = new ArrayDeque<>();
            WindowFunction.Collector<R> collector = buffer::addLast;
            try {
                fn.apply(key.key, key.window(), w.elements, collector);
            } catch (Exception e) {
                throw new RuntimeException("Window function failed", e);
            }
            while (!buffer.isEmpty()) {
                out.add(new InMemoryRecord<>(buffer.removeFirst(), w.lastTimestamp));
            }
        }
        return out;
    }

    private List<InMemoryRecord<Long>> countAll() {
        Map<WindowKey<K>, LongAcc> acc = new LinkedHashMap<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            for (WindowAssigner.Window window : windowAssigner.assignWindows(record.value(), record.timestamp())) {
                WindowKey<K> key = WindowKey.of(record.key(), window);
                LongAcc a = acc.get(key);
                if (a == null) {
                    a = new LongAcc(0L, record.timestamp());
                    acc.put(key, a);
                }
                a.value++;
                a.lastTimestamp = record.timestamp();
            }
        }
        List<InMemoryRecord<Long>> out = new ArrayList<>(acc.size());
        for (LongAcc a : acc.values()) {
            out.add(new InMemoryRecord<>(a.value, a.lastTimestamp));
        }
        return out;
    }

    private List<InMemoryRecord<T>> sumAll(Function<T, ? extends Number> fieldSelector) {
        Map<WindowKey<K>, NumberAcc> acc = new LinkedHashMap<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            T value = record.value();
            if (!(value instanceof Number)) {
                throw new UnsupportedOperationException(
                        "In-memory runtime window sum() only supports Number elements, but got: " +
                                (value == null ? "null" : value.getClass().getName()));
            }
            for (WindowAssigner.Window window : windowAssigner.assignWindows(value, record.timestamp())) {
                WindowKey<K> key = WindowKey.of(record.key(), window);
                NumberAcc a = acc.get(key);
                if (a == null) {
                    a = new NumberAcc(0L, record.timestamp(), (Number) value);
                    acc.put(key, a);
                }
                a.value = addNumbers(a.value, fieldSelector.apply(value));
                a.lastTimestamp = record.timestamp();
            }
        }
        List<InMemoryRecord<T>> out = new ArrayList<>(acc.size());
        for (NumberAcc a : acc.values()) {
            @SuppressWarnings("unchecked")
            T v = (T) castToSameNumberType(a.value, a.sample);
            out.add(new InMemoryRecord<>(v, a.lastTimestamp));
        }
        return out;
    }

    private static Number addNumbers(Number a, Number b) {
        if (b == null) {
            return a == null ? 0L : a;
        }
        if (a == null) {
            return b;
        }
        if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
            return a.doubleValue() + b.doubleValue();
        }
        return a.longValue() + b.longValue();
    }

    private static Number castToSameNumberType(Number value, Number sample) {
        if (sample instanceof Integer) return value.intValue();
        if (sample instanceof Long) return value.longValue();
        if (sample instanceof Double) return value.doubleValue();
        if (sample instanceof Float) return value.floatValue();
        if (sample instanceof Short) return value.shortValue();
        if (sample instanceof Byte) return value.byteValue();
        return value;
    }

    private static final class Acc<T> {
        private T value;
        private long lastTimestamp;

        private Acc(T value, long lastTimestamp) {
            this.value = value;
            this.lastTimestamp = lastTimestamp;
        }
    }

    private static final class AggregateAcc<T> {
        private AggregateFunction.Accumulator<T> accumulator;
        private long lastTimestamp;

        private AggregateAcc(AggregateFunction.Accumulator<T> accumulator, long lastTimestamp) {
            this.accumulator = accumulator;
            this.lastTimestamp = lastTimestamp;
        }
    }

    private static final class WindowValues<T> {
        private final List<T> elements;
        private long lastTimestamp;

        private WindowValues(List<T> elements, long lastTimestamp) {
            this.elements = elements;
            this.lastTimestamp = lastTimestamp;
        }
    }

    private static final class LongAcc {
        private long value;
        private long lastTimestamp;

        private LongAcc(long value, long lastTimestamp) {
            this.value = value;
            this.lastTimestamp = lastTimestamp;
        }
    }

    private static final class NumberAcc {
        private Number value;
        private long lastTimestamp;
        private final Number sample;

        private NumberAcc(Number value, long lastTimestamp, Number sample) {
            this.value = value;
            this.lastTimestamp = lastTimestamp;
            this.sample = sample;
        }
    }

    private static final class WindowKey<K> {
        private final K key;
        private final long start;
        private final long end;

        private WindowKey(K key, long start, long end) {
            this.key = key;
            this.start = start;
            this.end = end;
        }

        static <K> WindowKey<K> of(K key, WindowAssigner.Window window) {
            return new WindowKey<>(key, window.getStart(), window.getEnd());
        }

        WindowAssigner.Window window() {
            return new SimpleWindow(start, end);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WindowKey<?> windowKey = (WindowKey<?>) o;
            return start == windowKey.start && end == windowKey.end && Objects.equals(key, windowKey.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, start, end);
        }
    }

    private record SimpleWindow(long start, long end) implements WindowAssigner.Window {
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

