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
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class InMemoryKeyedStream<K, T> implements KeyedStream<K, T> {

    private final InMemoryKeyedStateStore<K> stateStore;
    private final Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier;

    public InMemoryKeyedStream(Supplier<Iterator<T>> iteratorSupplier, Function<T, K> keySelector) {
        this(new InMemoryKeyedStateStore<>(), () -> new Iterator<>() {
            private final Iterator<T> it = Objects.requireNonNull(iteratorSupplier, "iteratorSupplier").get();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public KeyedRecord<K, T> next() {
                T v = it.next();
                return new KeyedRecord<>(Objects.requireNonNull(keySelector, "keySelector").apply(v), v);
            }
        });
    }

    private InMemoryKeyedStream(InMemoryKeyedStateStore<K> stateStore,
                                Supplier<Iterator<KeyedRecord<K, T>>> keyedIteratorSupplier) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.keyedIteratorSupplier = Objects.requireNonNull(keyedIteratorSupplier, "keyedIteratorSupplier");
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
                return new KeyedRecord<>(r.key(), mapper.apply(r.value()));
            }
        });
    }

    @Override
    public <R> DataStream<R> process(KeyedProcessFunction<K, T, R> processFunction) {
        Objects.requireNonNull(processFunction, "processFunction");
        return new InMemoryDataStream<>(() -> new Iterator<>() {
            private final Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
            private final ArrayDeque<R> buffer = new ArrayDeque<>();
            private final KeyedProcessFunction.Context ctx = new InMemoryProcessContext();
            private final KeyedProcessFunction.Collector<R> out = buffer::addLast;

            @Override
            public boolean hasNext() {
                while (buffer.isEmpty() && in.hasNext()) {
                    KeyedRecord<K, T> record = in.next();
                    stateStore.setCurrentKey(record.key());
                    try {
                        processFunction.processElement(record.key(), record.value(), ctx, out);
                    } catch (Exception e) {
                        throw new RuntimeException("Keyed process function failed", e);
                    }
                }
                return !buffer.isEmpty();
            }

            @Override
            public R next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return buffer.removeFirst();
            }
        });
    }

    @Override
    public WindowedStream<K, T> window(WindowAssigner<T> windowAssigner) {
        throw new UnsupportedOperationException("In-memory runtime windowing is not implemented yet");
    }

    @Override
    public DataStream<T> reduce(ReduceFunction<T> reducer) {
        Objects.requireNonNull(reducer, "reducer");
        return new InMemoryDataStream<>(() -> new Iterator<>() {
            private final Iterator<KeyedRecord<K, T>> in = keyedIteratorSupplier.get();
            private final Map<K, T> acc = new HashMap<>();
            private final ArrayDeque<T> buffer = new ArrayDeque<>();

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
                    buffer.addLast(reduced);
                }
                return !buffer.isEmpty();
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return buffer.removeFirst();
            }
        });
    }

    @Override
    public DataStream<T> sum(Function<T, ? extends Number> fieldSelector) {
        throw new UnsupportedOperationException("In-memory runtime sum() is not implemented yet");
    }

    @Override
    public <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor) {
        return new InMemoryKeyedValueState<>(stateStore, stateDescriptor);
    }

    private static final class InMemoryProcessContext implements KeyedProcessFunction.Context {
        @Override
        public long currentProcessingTime() {
            return System.currentTimeMillis();
        }

        @Override
        public long currentWatermark() {
            return Long.MIN_VALUE;
        }

        @Override
        public void registerProcessingTimeTimer(long time) {
            // not implemented
        }

        @Override
        public void registerEventTimeTimer(long time) {
            // not implemented
        }
    }
}
