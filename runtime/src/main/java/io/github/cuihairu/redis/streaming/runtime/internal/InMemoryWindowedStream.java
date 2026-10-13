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
 * <p>When the input is exhausted the effective watermark is {@code +inf}: every remaining
 * non-empty bucket gets a final {@link WindowAssigner.Trigger#onEventTime} callback (with the
 * window end) followed by a flush, so no accumulated data is silently dropped. When the stream
 * carries a {@link WatermarkState} (from {@code assignTimestampsAndWatermarks} upstream of the
 * {@code window} operator) buckets whose end the current watermark already passed close
 * mid-stream through the same {@code onEventTime} callback — that is what makes event-time
 * firing possible before an unbounded input ends.
 * {@link WindowAssigner.Trigger#onProcessingTime} is invoked by
 * {@link #closeReachedWindowsByProcessingTime}, which fires every bucket whose window end the
 * current record's timestamp has passed (the in-memory engine's processing-time clock).</p>
 *
 * <p>Accumulation is incremental: {@code reduce} / {@code sum} / {@code count} /
 * {@code aggregate} keep a per-bucket accumulator (fold value, running total, counter,
 * {@code AggregateFunction} accumulator) instead of the raw elements, so memory stays bounded
 * by the number of windows rather than the number of records. {@code apply} and assigners with
 * {@link WindowAssigner#supportsWindowMerging()} keep the raw element list: {@code apply} needs
 * the whole window content, and session merging must re-accumulate the union of absorbed
 * elements (the trigger API has no merge callback for incremental accumulators).</p>
 *
 * <p>For assigners with {@link WindowAssigner#supportsWindowMerging()} (session windows), a
 * newly assigned window is first coalesced with every same-key bucket it intersects: the union
 * window takes over all accumulated elements, so a whole session fires as one result.</p>
 *
 * <p>When the pipeline carries a checkpoint coordinator, each terminal-op consumption owns a
 * per-consumption {@link OperatorState operator state} whose bucket map registers as a
 * {@code window-N} snapshot source: checkpoints cut a consistent view of the accumulated
 * buckets (per-record fence, see {@link #drive}), and restore is FRESH by default — restored
 * buckets are kept for inspection and never injected, because in-memory sources replay their
 * full history (see docs/InMemory-Operator-State-Lifecycle-Design.md). The state rides as a
 * field of the per-consumption iterator, not a lambda local: javac 17 (the build's target JDK)
 * fails to resolve a lambda-body local captured by the anonymous iterator class, even though
 * later compilers accept it.</p>
 */
final class InMemoryWindowedStream<K, T> implements WindowedStream<K, T> {

    private final Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier;
    private final WindowAssigner<T> windowAssigner;
    private final WatermarkState watermarkState;
    private final InMemoryCheckpointCoordinator checkpointCoordinator;
    /**
     * The monitor window-bucket mutations synchronize on: the coordinator's checkpoint lock
     * when this pipeline participates in checkpoints, an uncontended private lock otherwise —
     * the same per-record-barrier contract the keyed path gives its state store.
     */
    private final Object fenceLock;

    InMemoryWindowedStream(Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier,
                           WindowAssigner<T> windowAssigner) {
        this(keyedIteratorSupplier, windowAssigner, null);
    }

    InMemoryWindowedStream(Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier,
                           WindowAssigner<T> windowAssigner,
                           WatermarkState watermarkState) {
        this(keyedIteratorSupplier, windowAssigner, watermarkState, null);
    }

    InMemoryWindowedStream(Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier,
                           WindowAssigner<T> windowAssigner,
                           WatermarkState watermarkState,
                           InMemoryCheckpointCoordinator checkpointCoordinator) {
        this.keyedIteratorSupplier = Objects.requireNonNull(keyedIteratorSupplier, "keyedIteratorSupplier");
        this.windowAssigner = Objects.requireNonNull(windowAssigner, "windowAssigner");
        this.watermarkState = watermarkState;
        this.checkpointCoordinator = checkpointCoordinator;
        this.fenceLock = checkpointCoordinator != null
                ? checkpointCoordinator.checkpointLock()
                : new Object();
    }

    @Override
    public DataStream<T> reduce(ReduceFunction<T> reducer) {
        Objects.requireNonNull(reducer, "reducer");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            // One operator state per consumption (per sink): the bucket map lives and registers
            // per drive run, exactly as the method-local map always has.
            private final OperatorState<T> op = new OperatorState<>();
            private Iterator<InMemoryRecord<T>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = reduceAll(reducer, op).iterator();
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
            private final OperatorState<R> op = new OperatorState<>();
            private Iterator<InMemoryRecord<R>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = aggregateAll(aggregateFunction, op).iterator();
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
            private final OperatorState<R> op = new OperatorState<>();
            private Iterator<InMemoryRecord<R>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = applyAll(windowFunction, op).iterator();
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
            private final OperatorState<T> op = new OperatorState<>();
            private Iterator<InMemoryRecord<T>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = sumAll(fieldSelector, op).iterator();
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
            private final OperatorState<Long> op = new OperatorState<>();
            private Iterator<InMemoryRecord<Long>> out;

            @Override
            public boolean hasNext() {
                if (out == null) {
                    out = countAll(op).iterator();
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
     * Computes the emission(s) of one bucket fire from its accumulated raw elements
     * (the raw path used by {@code apply} and session-window merging).
     * Implementations wrap user-function failures in the runtime's legacy
     * error messages before rethrowing.
     */
    @FunctionalInterface
    private interface FireComputer<KK, TT, R> {
        List<R> compute(KK key, WindowAssigner.Window window, List<TT> elements);
    }

    /** Per-bucket incremental accumulator used instead of the raw element list. */
    private interface BucketState<TT, R> {
        void add(TT value);

        boolean isEmpty();

        /** The result(s) emitted when this bucket fires. */
        List<R> fire();
    }

    /**
     * Folds a raw element list into a fresh accumulator, so the raw path (apply, session-window
     * merging) reuses the incremental accumulators instead of re-implementing the fold.
     */
    private <R> FireComputer<K, T, R> rawFromStates(Supplier<BucketState<T, R>> states) {
        return (key, window, elements) -> {
            BucketState<T, R> state = states.get();
            for (T element : elements) {
                state.add(element);
            }
            return state.fire();
        };
    }

    private List<InMemoryRecord<T>> reduceAll(ReduceFunction<T> reducer, OperatorState<T> op) {
        Supplier<BucketState<T, T>> states = () -> new FoldState<T>(reducer);
        return drive(op, states, false, rawFromStates(states));
    }

    private <R> List<InMemoryRecord<R>> aggregateAll(AggregateFunction<T, R> fn, OperatorState<R> op) {
        Supplier<BucketState<T, R>> states = () -> new AggregateState<T, R>(fn);
        return drive(op, states, false, rawFromStates(states));
    }

    private <R> List<InMemoryRecord<R>> applyAll(WindowFunction<K, T, R> fn, OperatorState<R> op) {
        return drive(op, null, true, (key, window, elements) -> {
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

    private List<InMemoryRecord<Long>> countAll(OperatorState<Long> op) {
        Supplier<BucketState<T, Long>> states = CountState::new;
        return drive(op, states, false, rawFromStates(states));
    }

    private List<InMemoryRecord<T>> sumAll(Function<T, ? extends Number> fieldSelector, OperatorState<T> op) {
        Supplier<BucketState<T, T>> states = () -> new SumState<T>(fieldSelector);
        return drive(op, states, false, rawFromStates(states));
    }

    private <R> List<InMemoryRecord<R>> drive(OperatorState<R> op,
                                              Supplier<BucketState<T, R>> newState,
                                              boolean rawElements,
                                              FireComputer<K, T, R> rawComputer) {
        boolean merging = windowAssigner.supportsWindowMerging();
        Map<WindowKey<K>, Bucket<T, R>> buckets = op.buckets();
        List<InMemoryRecord<R>> out = new ArrayList<>();
        Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
        while (in.hasNext()) {
            KeyedRecord<K, T> record = in.next();
            // Per-record barrier (same contract as the keyed path): the record's whole effect
            // on the window buckets runs under the fence, so a checkpoint triggered mid-record
            // blocks until the record is done instead of splitting its bucket updates. The
            // source iterator is consumed outside the fence — a blocking source must not
            // freeze checkpoints between records.
            synchronized (fenceLock) {
                for (WindowAssigner.Window window : windowAssigner.assignWindows(record.value(), record.timestamp())) {
                    WindowKey<K> wk = WindowKey.of(record.key(), window);
                    if (merging) {
                        wk = mergeIntersectingBuckets(record.key(), wk, buckets);
                        window = wk.window();
                    }
                    boolean raw = rawElements || merging;
                    Bucket<T, R> bucket = buckets.computeIfAbsent(wk, key -> raw
                            ? Bucket.raw(windowAssigner.getDefaultTrigger())
                            : Bucket.incremental(windowAssigner.getDefaultTrigger(), newState.get()));
                    if (bucket.raw != null) {
                        bucket.raw.add(record.value());
                    } else {
                        bucket.state.add(record.value());
                    }
                    bucket.lastTimestamp = record.timestamp();
                    WindowAssigner.TriggerResult result =
                            bucket.trigger.onElement(record.value(), record.timestamp(), wk.window());
                    if (result == WindowAssigner.TriggerResult.FIRE) {
                        fire(wk, bucket, rawComputer, out);
                    } else if (result == WindowAssigner.TriggerResult.FIRE_AND_PURGE) {
                        fire(wk, bucket, rawComputer, out);
                        purge(bucket, newState);
                    } else if (result == WindowAssigner.TriggerResult.PURGE) {
                        purge(bucket, newState);
                    }
                    // CONTINUE: keep accumulating
                }
                if (watermarkState != null) {
                    closeReachedWindows(watermarkState.getWatermark(), buckets, rawComputer, out);
                }
                closeReachedWindowsByProcessingTime(record.timestamp(), buckets, rawComputer, out);
            }
        }
        // Input exhausted: the effective watermark is +inf. Give every remaining bucket a
        // final onEventTime callback at the window end, then flush what is left. The flush can
        // also fire buckets and consult triggers; fence it the same way (same reasoning as the
        // keyed path's end-of-input drainAllTimers).
        synchronized (fenceLock) {
            for (Map.Entry<WindowKey<K>, Bucket<T, R>> entry : buckets.entrySet()) {
                Bucket<T, R> bucket = entry.getValue();
                WindowAssigner.Window window = entry.getKey().window();
                bucket.trigger.onEventTime(window.getEnd(), window);
                fire(entry.getKey(), bucket, rawComputer, out);
            }
        }
        return out;
    }

    /**
     * Closes every bucket whose window end the current watermark already passed, which is what
     * emits windows before an unbounded input ends. Buckets that answered {@code CONTINUE} or
     * {@code FIRE} stay open and get the final flush at end of input.
     */
    private <R> void closeReachedWindows(long watermark, Map<WindowKey<K>, Bucket<T, R>> buckets,
                                         FireComputer<K, T, R> rawComputer, List<InMemoryRecord<R>> out) {
        if (watermark == Long.MIN_VALUE) {
            return;
        }
        for (Iterator<Map.Entry<WindowKey<K>, Bucket<T, R>>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<WindowKey<K>, Bucket<T, R>> entry = it.next();
            WindowKey<K> wk = entry.getKey();
            if (wk.end > watermark) {
                continue;
            }
            Bucket<T, R> bucket = entry.getValue();
            WindowAssigner.TriggerResult result = bucket.trigger.onEventTime(wk.end, wk.window());
            if (result == WindowAssigner.TriggerResult.FIRE
                    || result == WindowAssigner.TriggerResult.FIRE_AND_PURGE) {
                fire(wk, bucket, rawComputer, out);
            }
            if (result == WindowAssigner.TriggerResult.FIRE_AND_PURGE
                    || result == WindowAssigner.TriggerResult.PURGE) {
                it.remove();
            }
        }
    }

    /**
     * Processing-time sweep. The in-memory engine has no wall clock of its own: its
     * processing-time clock is the timestamp of the record currently being processed (the same
     * contract {@code InMemoryKeyedStream} uses for {@code KeyedProcessFunction} timers), so a
     * bucket fires here once a record's timestamp passes its window end. This is the only
     * mid-stream fire path when no {@link WatermarkState} is present.
     */
    private <R> void closeReachedWindowsByProcessingTime(long processingTime,
                                                        Map<WindowKey<K>, Bucket<T, R>> buckets,
                                                        FireComputer<K, T, R> rawComputer,
                                                        List<InMemoryRecord<R>> out) {
        if (processingTime == Long.MIN_VALUE) {
            return;
        }
        for (Iterator<Map.Entry<WindowKey<K>, Bucket<T, R>>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<WindowKey<K>, Bucket<T, R>> entry = it.next();
            WindowKey<K> wk = entry.getKey();
            if (wk.end > processingTime) {
                continue;
            }
            Bucket<T, R> bucket = entry.getValue();
            WindowAssigner.TriggerResult result = bucket.trigger.onProcessingTime(processingTime, wk.window());
            if (result == WindowAssigner.TriggerResult.FIRE
                    || result == WindowAssigner.TriggerResult.FIRE_AND_PURGE) {
                fire(wk, bucket, rawComputer, out);
            }
            if (result == WindowAssigner.TriggerResult.FIRE_AND_PURGE
                    || result == WindowAssigner.TriggerResult.PURGE) {
                it.remove();
            }
        }
    }

    private <R> void fire(WindowKey<K> wk, Bucket<T, R> bucket, FireComputer<K, T, R> rawComputer,
                          List<InMemoryRecord<R>> out) {
        List<R> results;
        if (bucket.raw != null) {
            if (bucket.raw.isEmpty()) {
                return;
            }
            results = rawComputer.compute(wk.key, wk.window(), bucket.raw);
        } else {
            if (bucket.state.isEmpty()) {
                return;
            }
            results = bucket.state.fire();
        }
        for (R r : results) {
            out.add(new InMemoryRecord<>(r, bucket.lastTimestamp));
        }
    }

    private <R> void purge(Bucket<T, R> bucket, Supplier<BucketState<T, R>> newState) {
        if (bucket.raw != null) {
            bucket.raw.clear();
        } else {
            bucket.state = newState.get();
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
    private <R> WindowKey<K> mergeIntersectingBuckets(K key, WindowKey<K> seed,
                                                      Map<WindowKey<K>, Bucket<T, R>> buckets) {
        long start = seed.start;
        long end = seed.end;
        List<Bucket<T, R>> absorbed = new ArrayList<>();
        for (Iterator<Map.Entry<WindowKey<K>, Bucket<T, R>>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<WindowKey<K>, Bucket<T, R>> entry = it.next();
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
        Bucket<T, R> merged = Bucket.raw(absorbed.get(0).trigger);
        for (Bucket<T, R> bucket : absorbed) {
            merged.raw.addAll(bucket.raw);
            merged.lastTimestamp = Math.max(merged.lastTimestamp, bucket.lastTimestamp);
        }
        buckets.put(mergedKey, merged);
        return mergedKey;
    }

    /**
     * Per-consumption window operator state (operator-state lifecycle contract, see
     * docs/InMemory-Operator-State-Lifecycle-Design.md): one instance per terminal-op
     * consumption — the bucket map {@link #drive} populates plus its snapshot-source
     * registration. Registered lazily at consumption start, so an unconsumed terminal op never
     * registers. Restore is FRESH by default: the restored payload is kept for inspection in
     * {@code restoredView} and never injected into the live buckets, because in-memory sources
     * replay their full history — adopting restored buckets and replaying the same records
     * would double-count every window.
     */
    private final class OperatorState<R> {
        private final Map<WindowKey<K>, Bucket<T, R>> buckets = new LinkedHashMap<>();
        private volatile Object restoredView;

        OperatorState() {
            if (checkpointCoordinator != null) {
                checkpointCoordinator.registerGeneratedSource("window",
                        this::snapshotBuckets, this::restoreSnapshot);
            }
        }

        Map<WindowKey<K>, Bucket<T, R>> buckets() {
            return buckets;
        }

        /**
         * Checkpoint snapshot payload: one entry per non-empty bucket — key, window bounds,
         * last element timestamp, and the bucket's accumulated contents (the incremental
         * path's current fire results, or a copy of the raw element list for apply/session
         * buckets). The bucket's Trigger instance is deliberately not captured: Trigger has
         * no snapshot API, the same boundary the session-merge path already documents.
         */
        List<BucketContent> snapshotBuckets() {
            List<BucketContent> out = new ArrayList<>(buckets.size());
            for (Map.Entry<WindowKey<K>, Bucket<T, R>> entry : buckets.entrySet()) {
                WindowKey<K> wk = entry.getKey();
                Bucket<T, R> bucket = entry.getValue();
                List<?> contents;
                if (bucket.raw != null) {
                    if (bucket.raw.isEmpty()) {
                        continue;
                    }
                    contents = List.copyOf(bucket.raw);
                } else {
                    if (bucket.state.isEmpty()) {
                        continue;
                    }
                    contents = bucket.state.fire();
                }
                out.add(new BucketContent(wk.key, wk.start, wk.end, bucket.lastTimestamp, contents));
            }
            return out;
        }

        /** FRESH restore: keep the payload for inspection, never inject it into the live buckets. */
        void restoreSnapshot(Object snapshot) {
            if (snapshot instanceof List<?> list) {
                this.restoredView = List.copyOf(list);
            }
        }
    }

    /** Checkpoint snapshot payload for one window bucket (see {@link OperatorState}). */
    record BucketContent(Object key, long start, long end, long lastTimestamp, List<?> contents) {
    }

    /** Per-(key, window) bucket: the bucket's own trigger instance plus either its raw elements
     *  or its incremental accumulator (exactly one of the two is non-null). */
    private static final class Bucket<TT, R> {
        private final WindowAssigner.Trigger<TT> trigger;
        private final List<TT> raw;
        private BucketState<TT, R> state;
        private long lastTimestamp;

        private Bucket(WindowAssigner.Trigger<TT> trigger, List<TT> raw, BucketState<TT, R> state) {
            this.trigger = trigger;
            this.raw = raw;
            this.state = state;
        }

        private static <TT, R> Bucket<TT, R> raw(WindowAssigner.Trigger<TT> trigger) {
            return new Bucket<>(trigger, new ArrayList<>(), null);
        }

        private static <TT, R> Bucket<TT, R> incremental(WindowAssigner.Trigger<TT> trigger,
                                                         BucketState<TT, R> state) {
            return new Bucket<>(trigger, null, state);
        }
    }

    /** {@code reduce} accumulator: the running fold of every element so far. */
    private static final class FoldState<TT> implements BucketState<TT, TT> {
        private final ReduceFunction<TT> reducer;
        private TT acc;
        private boolean hasElements;

        private FoldState(ReduceFunction<TT> reducer) {
            this.reducer = reducer;
        }

        @Override
        public void add(TT value) {
            try {
                acc = acc == null ? value : reducer.reduce(acc, value);
            } catch (Exception e) {
                throw new RuntimeException("Window reduce function failed", e);
            }
            hasElements = true;
        }

        @Override
        public boolean isEmpty() {
            return !hasElements;
        }

        @Override
        public List<TT> fire() {
            return List.of(acc);
        }
    }

    /** {@code aggregate} accumulator: a running {@code AggregateFunction} accumulator. */
    private static final class AggregateState<TT, R> implements BucketState<TT, R> {
        private final AggregateFunction<TT, R> fn;
        private AggregateFunction.Accumulator<TT> acc;

        private AggregateState(AggregateFunction<TT, R> fn) {
            this.fn = fn;
            this.acc = fn.createAccumulator();
        }

        @Override
        public void add(TT value) {
            acc = fn.add(value, acc);
        }

        @Override
        public boolean isEmpty() {
            return acc == null;
        }

        @Override
        public List<R> fire() {
            return List.of(fn.getResult(acc));
        }
    }

    /** {@code sum} accumulator: running total plus the first element's number type. */
    private static final class SumState<TT> implements BucketState<TT, TT> {
        private final Function<TT, ? extends Number> fieldSelector;
        private Number total;
        private Number sample;
        private boolean hasElements;

        private SumState(Function<TT, ? extends Number> fieldSelector) {
            this.fieldSelector = fieldSelector;
        }

        @Override
        public void add(TT value) {
            if (!hasElements && !(value instanceof Number numberSample)) {
                throw new UnsupportedOperationException(
                        "In-memory runtime window sum() only supports Number elements, but got: " +
                                (value == null ? "null" : value.getClass().getName()));
            }
            total = NumberAggregationUtils.add(total, fieldSelector.apply(value));
            if (!hasElements) {
                sample = (Number) value;
                hasElements = true;
            }
        }

        @Override
        public boolean isEmpty() {
            return !hasElements;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<TT> fire() {
            TT value = (TT) NumberAggregationUtils.castToSameType(total, sample);
            return List.of(value);
        }
    }

    /** {@code count} accumulator: an element counter. */
    private static final class CountState<TT> implements BucketState<TT, Long> {
        private long count;

        @Override
        public void add(TT value) {
            count++;
        }

        @Override
        public boolean isEmpty() {
            return count == 0;
        }

        @Override
        public List<Long> fire() {
            return List.of(count);
        }
    }

    private static final class WindowKey<KK> {
        private final KK key;
        private final long start;
        private final long end;

        private WindowKey(KK key, long start, long end) {
            this.key = key;
            this.start = start;
            this.end = end;
        }

        static <KK> WindowKey<KK> of(KK key, WindowAssigner.Window window) {
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
