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

/**
 * In-memory windowed stream whose firing is driven by the assigner's
 * {@link WindowAssigner#getDefaultTrigger()} trigger.
 *
 * <p>Each (key, window) bucket owns a dedicated trigger instance obtained from
 * {@link WindowAssigner#getDefaultTrigger()} (see its contract: fresh instance per call).
 * Element ingestion consults {@link WindowAssigner.Trigger#onElement}:</p>
 * <ul>
 *   <li>{@code FIRE} — emit the current partial result, keep the contents accumulating;</li>
 *   <li>{@code FIRE_AND_PURGE} — emit the current partial result and clear the contents;</li>
 *   <li>{@code PURGE} — clear the contents without emitting;</li>
 *   <li>{@code CONTINUE} — keep accumulating.</li>
 * </ul>
 *
 * <p>When the bounded input is exhausted the effective watermark is {@code +inf}: every remaining
 * non-empty bucket gets a final {@link WindowAssigner.Trigger#onEventTime} callback (with the
 * window end) followed by a flush, so no accumulated data is silently dropped.
 * {@link WindowAssigner.Trigger#onProcessingTime} is never invoked — the batch in-memory engine
 * has no processing-time timers.</p>
 *
 * <p>For assigners with {@link WindowAssigner#supportsWindowMerging()} (session windows), a newly
 * assigned window is first coalesced with every same-key bucket it intersects: the union window
 * takes over all accumulated elements, so a whole session fires as one result.</p>
 */
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

    /**
     * Computes the emission(s) of one bucket fire from its accumulated elements.
     * Implementations wrap user-function failures in the runtime's legacy
     * error messages before rethrowing.
     */
    @FunctionalInterface
    private interface FireComputer<K, T, R> {
        List<R> compute(K key, WindowAssigner.Window window, List<T> elements);
    }

    private <R> List<InMemoryRecord<R>> drive(FireComputer<K, T, R> computer) {
        Map<WindowKey<K>, Bucket<T>> buckets = new LinkedHashMap<>();
        List<InMemoryRecord<R>> out = new ArrayList<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            for (WindowAssigner.Window window : windowAssigner.assignWindows(record.value(), record.timestamp())) {
                WindowKey<K> wk = WindowKey.of(record.key(), window);
                if (windowAssigner.supportsWindowMerging()) {
                    wk = mergeIntersectingBuckets(record.key(), wk, buckets);
                    window = wk.window();
                }
                Bucket<T> bucket = buckets.computeIfAbsent(wk, k -> new Bucket<>(windowAssigner.getDefaultTrigger()));
                bucket.elements.add(record.value());
                bucket.lastTimestamp = record.timestamp();
                WindowAssigner.TriggerResult result =
                        bucket.trigger.onElement(record.value(), record.timestamp(), window);
                if (result == WindowAssigner.TriggerResult.FIRE) {
                    emit(wk, bucket, computer, out);
                } else if (result == WindowAssigner.TriggerResult.FIRE_AND_PURGE) {
                    emit(wk, bucket, computer, out);
                    bucket.elements.clear();
                } else if (result == WindowAssigner.TriggerResult.PURGE) {
                    bucket.elements.clear();
                }
                // CONTINUE: keep accumulating
            }
        }
        // Bounded input ended: the effective watermark is +inf. Give every remaining bucket a
        // final onEventTime callback at the window end, then flush what is left.
        for (Map.Entry<WindowKey<K>, Bucket<T>> entry : buckets.entrySet()) {
            Bucket<T> bucket = entry.getValue();
            WindowAssigner.Window window = entry.getKey().window();
            bucket.trigger.onEventTime(window.getEnd(), window);
            emit(entry.getKey(), bucket, computer, out);
            bucket.elements.clear();
        }
        return out;
    }

    private <R> void emit(WindowKey<K> wk, Bucket<T> bucket, FireComputer<K, T, R> computer,
                          List<InMemoryRecord<R>> out) {
        if (bucket.elements.isEmpty()) {
            return;
        }
        for (R r : computer.compute(wk.key, wk.window(), bucket.elements)) {
            out.add(new InMemoryRecord<>(r, bucket.lastTimestamp));
        }
    }

    /**
     * Coalesces every bucket of {@code key} whose {@code [start, end)} window intersects
     * {@code seed} (including the seed bucket itself, when present) into one bucket keyed by the
     * union window, and returns that key. Windows are half-open, so a window ending exactly where
     * another begins (an inactivity gap of exactly the session gap) does not merge.
     *
     * <p>Pre-existing buckets of a key are pairwise non-intersecting (this method upholds that
     * invariant), which is why a single scan suffices: a bucket skipped as non-intersecting now
     * can never intersect the final union, because that would mean it intersected one of the
     * absorbed stored buckets.
     *
     * <p>The merged bucket keeps the first absorbed bucket's trigger instance; the trigger state
     * of the remaining absorbed buckets is dropped (the trigger API has no merge callback — see
     * {@link WindowAssigner#supportsWindowMerging()}).
     */
    private WindowKey<K> mergeIntersectingBuckets(K key, WindowKey<K> seed,
                                                  Map<WindowKey<K>, Bucket<T>> buckets) {
        long start = seed.start;
        long end = seed.end;
        List<Bucket<T>> absorbed = new ArrayList<>();
        for (Iterator<Map.Entry<WindowKey<K>, Bucket<T>>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<WindowKey<K>, Bucket<T>> entry = it.next();
            WindowKey<K> candidate = entry.getKey();
            if (!Objects.equals(candidate.key, key)
                    || candidate.end <= start || candidate.start >= end) {
                continue;
            }
            start = Math.min(start, candidate.start);
            end = Math.max(end, candidate.end);
            absorbed.add(entry.getValue());
            it.remove();
        }
        if (absorbed.isEmpty()) {
            return seed;
        }
        WindowKey<K> mergedKey = new WindowKey<>(key, start, end);
        Bucket<T> merged = new Bucket<>(absorbed.get(0).trigger);
        for (Bucket<T> bucket : absorbed) {
            merged.elements.addAll(bucket.elements);
            merged.lastTimestamp = Math.max(merged.lastTimestamp, bucket.lastTimestamp);
        }
        buckets.put(mergedKey, merged);
        return mergedKey;
    }

    private List<InMemoryRecord<T>> reduceAll(ReduceFunction<T> reducer) {
        return drive((key, window, elements) -> {
            T acc = elements.get(0);
            for (int i = 1; i < elements.size(); i++) {
                try {
                    acc = reducer.reduce(acc, elements.get(i));
                } catch (Exception e) {
                    throw new RuntimeException("Window reduce function failed", e);
                }
            }
            return List.of(acc);
        });
    }

    private <R> List<InMemoryRecord<R>> aggregateAll(AggregateFunction<T, R> fn) {
        return drive((key, window, elements) -> {
            AggregateFunction.Accumulator<T> acc = fn.createAccumulator();
            for (T element : elements) {
                acc = fn.add(element, acc);
            }
            return List.of(fn.getResult(acc));
        });
    }

    private <R> List<InMemoryRecord<R>> applyAll(WindowFunction<K, T, R> fn) {
        return drive((key, window, elements) -> {
            ArrayDeque<R> buffer = new ArrayDeque<>();
            WindowFunction.Collector<R> collector = buffer::addLast;
            try {
                fn.apply(key, window, elements, collector);
            } catch (Exception e) {
                throw new RuntimeException("Window function failed", e);
            }
            return new ArrayList<>(buffer);
        });
    }

    private List<InMemoryRecord<Long>> countAll() {
        return drive((key, window, elements) -> List.of((long) elements.size()));
    }

    private List<InMemoryRecord<T>> sumAll(Function<T, ? extends Number> fieldSelector) {
        return drive((key, window, elements) -> {
            T sample = elements.get(0);
            if (!(sample instanceof Number numberSample)) {
                throw new UnsupportedOperationException(
                        "In-memory runtime window sum() only supports Number elements, but got: " +
                                (sample == null ? "null" : sample.getClass().getName()));
            }
            Number total = 0L;
            for (T element : elements) {
                total = NumberAggregationUtils.add(total, fieldSelector.apply(element));
            }
            @SuppressWarnings("unchecked")
            T value = (T) NumberAggregationUtils.castToSameType(total, numberSample);
            return List.of(value);
        });
    }

    /** Per-(key, window) bucket: the bucket's own trigger instance plus its raw contents. */
    private static final class Bucket<T> {
        private final WindowAssigner.Trigger<T> trigger;
        private final List<T> elements = new ArrayList<>();
        private long lastTimestamp;

        private Bucket(WindowAssigner.Trigger<T> trigger) {
            this.trigger = trigger;
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
