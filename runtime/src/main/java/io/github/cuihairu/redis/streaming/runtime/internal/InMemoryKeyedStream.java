package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public final class InMemoryKeyedStream<K, T> implements KeyedStream<K, T> {

    private final InMemoryKeyedStateStore<K> stateStore;
    private final Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier;
    private final WatermarkState watermarkState;

    public InMemoryKeyedStream(Supplier<Iterator<T>> iteratorSupplier, Function<T, K> keySelector) {
        this(new InMemoryKeyedStateStore<>(), () -> new Iterator<>() {
            private final Iterator<T> it = Objects.requireNonNull(iteratorSupplier, "iteratorSupplier").get();
            private long timestamp = 0L;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public KeyedRecord<K, T> next() {
                T v = it.next();
                return new KeyedRecord<>(Objects.requireNonNull(keySelector, "keySelector").apply(v), v, timestamp++);
            }
        }, null);
    }

    InMemoryKeyedStream(Supplier<Iterator<InMemoryRecord<T>>> recordIteratorSupplier,
                        Function<T, K> keySelector,
                        WatermarkState watermarkState) {
        this(new InMemoryKeyedStateStore<>(), () -> new Iterator<>() {
            private final Iterator<InMemoryRecord<T>> it =
                    Objects.requireNonNull(recordIteratorSupplier, "recordIteratorSupplier").get();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public KeyedRecord<K, T> next() {
                InMemoryRecord<T> record = it.next();
                T value = record.value();
                return new KeyedRecord<>(Objects.requireNonNull(keySelector, "keySelector").apply(value), value, record.timestamp());
            }
        }, watermarkState);
    }

    private InMemoryKeyedStream(InMemoryKeyedStateStore<K> stateStore,
                                Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier,
                                WatermarkState watermarkState) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.keyedIteratorSupplier = Objects.requireNonNull(keyedIteratorSupplier, "keyedIteratorSupplier");
        this.watermarkState = watermarkState;
    }

    @Override
    public <R> KeyedStream<K, R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new InMemoryKeyedStream<>(stateStore, () -> new Iterator<>() {
            private final Iterator<KeyedRecord<K, T>> it = keyedIteratorSupplier.get();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public KeyedRecord<K, R> next() {
                KeyedRecord<K, T> r = it.next();
                stateStore.setCurrentKey(r.key());
                return new KeyedRecord<>(r.key(), mapper.apply(r.value()), r.timestamp());
            }
        }, watermarkState);
    }

    @Override
    public <R> DataStream<R> process(KeyedProcessFunction<K, T, R> processFunction) {
        Objects.requireNonNull(processFunction, "processFunction");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private final Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
            private final ArrayDeque<InMemoryRecord<R>> buffer = new ArrayDeque<>();
            private final TimerQueue timerQueue = new TimerQueue();
            private final KeyedProcessFunction.Context ctx = new ContextImpl();
            private long currentTimestamp = Long.MIN_VALUE;
            private boolean inputDrained = false;
            private final KeyedProcessFunction.Collector<R> out = value ->
                    buffer.addLast(new InMemoryRecord<>(value, currentTimestamp));

            @Override
            public boolean hasNext() {
                while (buffer.isEmpty()) {
                    if (!inputDrained && in.hasNext()) {
                        KeyedRecord<K, T> record = in.next();
                        currentTimestamp = record.timestamp();
                        timerQueue.fireDueTimers();
                        stateStore.setCurrentKey(record.key());
                        try {
                            processFunction.processElement(record.key(), record.value(), ctx, out);
                        } catch (Exception e) {
                            throw new RuntimeException("Keyed process function failed", e);
                        }
                        timerQueue.fireDueTimers();
                        continue;
                    }

                    if (!inputDrained) {
                        inputDrained = true;
                        timerQueue.drainAllTimers();
                        continue;
                    }
                    break;
                }
                return !buffer.isEmpty();
            }

            @Override
            public InMemoryRecord<R> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return buffer.removeFirst();
            }

            private final class TimerQueue {
                private long seq = 0L;
                private final PriorityQueue<Timer<K>> processingTimers = new PriorityQueue<>(
                        (a, b) -> {
                            int c = Long.compare(a.timestamp(), b.timestamp());
                            if (c != 0) return c;
                            return Long.compare(a.seq(), b.seq());
                        }
                );
                private final PriorityQueue<Timer<K>> eventTimers = new PriorityQueue<>(
                        (a, b) -> {
                            int c = Long.compare(a.timestamp(), b.timestamp());
                            if (c != 0) return c;
                            return Long.compare(a.seq(), b.seq());
                        }
                );
                private final Set<TimerKey<K>> scheduled = new HashSet<>();

                void registerProcessingTimeTimer(long timestamp) {
                    register(timestamp, TimerType.PROCESSING_TIME);
                }

                void registerEventTimeTimer(long timestamp) {
                    register(timestamp, TimerType.EVENT_TIME);
                }

                private void register(long timestamp, TimerType type) {
                    K key = stateStore.currentKey();
                    if (key == null) {
                        throw new IllegalStateException("No current key set for timer registration");
                    }
                    TimerKey<K> timerKey = new TimerKey<>(key, timestamp, type);
                    if (!scheduled.add(timerKey)) {
                        return;
                    }
                    Timer<K> timer = new Timer<>(seq++, key, timestamp, type);
                    if (type == TimerType.PROCESSING_TIME) {
                        processingTimers.add(timer);
                    } else {
                        eventTimers.add(timer);
                    }
                }

                void fireDueTimers() {
                    long processingTime = currentTimestamp;
                    long watermark = currentWatermarkValue();
                    fireDueTimersBy(processingTimers, processingTime);
                    fireDueTimersBy(eventTimers, watermark);
                }

                void drainAllTimers() {
                    // End-of-input: advance watermark to max to flush event-time timers.
                    fireDueTimersBy(processingTimers, Long.MAX_VALUE);
                    fireDueTimersBy(eventTimers, Long.MAX_VALUE);
                }

                private void fireDueTimersBy(PriorityQueue<Timer<K>> queue, long upToTimestamp) {
                    while (!queue.isEmpty() && queue.peek().timestamp() <= upToTimestamp) {
                        Timer<K> timer = queue.poll();
                        scheduled.remove(new TimerKey<>(timer.key(), timer.timestamp(), timer.type()));
                        fire(timer);
                    }
                }

                private void fire(Timer<K> timer) {
                    long previousTimestamp = currentTimestamp;
                    stateStore.setCurrentKey(timer.key());
                    currentTimestamp = timer.timestamp();
                    try {
                        switch (timer.type()) {
                            case PROCESSING_TIME -> processFunction.onProcessingTime(timer.timestamp(), timer.key(), ctx, out);
                            case EVENT_TIME -> processFunction.onEventTime(timer.timestamp(), timer.key(), ctx, out);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Keyed process timer callback failed", e);
                    } finally {
                        currentTimestamp = previousTimestamp;
                    }
                    fireDueTimers();
                }
            }

            private final class ContextImpl implements KeyedProcessFunction.Context {
                @Override
                public long currentProcessingTime() {
                    return currentTimestamp;
                }

                @Override
                public long currentWatermark() {
                    return currentWatermarkValue();
                }

                @Override
                public void registerProcessingTimeTimer(long time) {
                    timerQueue.registerProcessingTimeTimer(time);
                }

                @Override
                public void registerEventTimeTimer(long time) {
                    timerQueue.registerEventTimeTimer(time);
                }
            }

            private long currentWatermarkValue() {
                if (watermarkState == null) {
                    return currentTimestamp;
                }
                return watermarkState.getWatermark();
            }
        });
    }

    private enum TimerType {
        PROCESSING_TIME,
        EVENT_TIME
    }

    private record TimerKey<KK>(KK key, long timestamp, TimerType type) {
    }

    private record Timer<KK>(long seq, KK key, long timestamp, TimerType type) {
    }

    @Override
    public WindowedStream<K, T> window(WindowAssigner<T> windowAssigner) {
        Objects.requireNonNull(windowAssigner, "windowAssigner");
        return new InMemoryWindowedStream<>(keyedIteratorSupplier, windowAssigner);
    }

    @Override
    public DataStream<T> reduce(ReduceFunction<T> reducer) {
        Objects.requireNonNull(reducer, "reducer");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private final Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
            private final Map<K, T> acc = new HashMap<>();
            private final ArrayDeque<InMemoryRecord<T>> buffer = new ArrayDeque<>();

            @Override
            public boolean hasNext() {
                while (buffer.isEmpty() && in.hasNext()) {
                    KeyedRecord<K, T> record = in.next();
                    K key = record.key();
                    T value = record.value();
                    stateStore.setCurrentKey(key);
                    T current = acc.get(key);
                    T reduced;
                    try {
                        reduced = current == null ? value : reducer.reduce(current, value);
                    } catch (Exception e) {
                        throw new RuntimeException("Reduce function failed", e);
                    }
                    acc.put(key, reduced);
                    buffer.addLast(new InMemoryRecord<>(reduced, record.timestamp()));
                }
                return !buffer.isEmpty();
            }

            @Override
            public InMemoryRecord<T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return buffer.removeFirst();
            }
        });
    }

    @Override
    public DataStream<T> sum(Function<T, ? extends Number> fieldSelector) {
        Objects.requireNonNull(fieldSelector, "fieldSelector");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private final Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
            private final Map<K, Number> acc = new HashMap<>();
            private final ArrayDeque<InMemoryRecord<T>> buffer = new ArrayDeque<>();

            @Override
            public boolean hasNext() {
                while (buffer.isEmpty() && in.hasNext()) {
                    KeyedRecord<K, T> record = in.next();
                    K key = record.key();
                    T value = record.value();
                    if (!(value instanceof Number)) {
                        throw new UnsupportedOperationException(
                                "In-memory runtime sum() only supports Number elements, but got: " +
                                        (value == null ? "null" : value.getClass().getName()));
                    }

                    stateStore.setCurrentKey(key);

                    Number current = acc.get(key);
                    Number next = addNumbers(current, fieldSelector.apply(value));
                    acc.put(key, next);
                    @SuppressWarnings("unchecked")
                    T out = (T) castToSameNumberType(next, (Number) value);
                    buffer.addLast(new InMemoryRecord<>(out, record.timestamp()));
                }
                return !buffer.isEmpty();
            }

            @Override
            public InMemoryRecord<T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return buffer.removeFirst();
            }
        });
    }

    @Override
    public <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor) {
        return new InMemoryKeyedValueState<>(stateStore, stateDescriptor);
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
}
