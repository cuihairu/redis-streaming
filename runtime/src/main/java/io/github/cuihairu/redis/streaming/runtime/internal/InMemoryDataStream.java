package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class InMemoryDataStream<T> implements DataStream<T>, Iterable<T> {
    private static final Logger log = LoggerFactory.getLogger(InMemoryDataStream.class);

    private final Supplier<Iterator<T>> iteratorSupplier;

    public InMemoryDataStream(Supplier<Iterator<T>> iteratorSupplier) {
        this.iteratorSupplier = Objects.requireNonNull(iteratorSupplier, "iteratorSupplier");
    }

    @Override
    public Iterator<T> iterator() {
        return iteratorSupplier.get();
    }

    @Override
    public <R> DataStream<R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new InMemoryDataStream<>(() -> new Iterator<>() {
            private final Iterator<T> it = iteratorSupplier.get();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public R next() {
                return mapper.apply(it.next());
            }
        });
    }

    @Override
    public DataStream<T> filter(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return new InMemoryDataStream<>(() -> new Iterator<>() {
            private final Iterator<T> it = iteratorSupplier.get();
            private boolean computed = false;
            private boolean hasNext = false;
            private T next;

            @Override
            public boolean hasNext() {
                if (computed) {
                    return hasNext;
                }
                computed = true;
                while (it.hasNext()) {
                    T candidate = it.next();
                    if (predicate.test(candidate)) {
                        next = candidate;
                        hasNext = true;
                        return true;
                    }
                }
                next = null;
                hasNext = false;
                return false;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                computed = false;
                T out = next;
                next = null;
                return out;
            }
        });
    }

    @Override
    public <R> DataStream<R> flatMap(Function<T, Iterable<R>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new InMemoryDataStream<>(() -> new Iterator<>() {
            private final Iterator<T> it = iteratorSupplier.get();
            private Iterator<R> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && it.hasNext()) {
                    Iterable<R> mapped = mapper.apply(it.next());
                    current = mapped == null ? Collections.emptyIterator() : mapped.iterator();
                }
                return current.hasNext();
            }

            @Override
            public R next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        });
    }

    @Override
    public <K> KeyedStream<K, T> keyBy(Function<T, K> keySelector) {
        Objects.requireNonNull(keySelector, "keySelector");
        return new InMemoryKeyedStream<>(iteratorSupplier, keySelector);
    }

    @Override
    public DataStream<T> addSink(StreamSink<T> sink) {
        Objects.requireNonNull(sink, "sink");
        for (T v : this) {
            try {
                sink.invoke(v);
            } catch (Exception e) {
                throw new RuntimeException("Sink invocation failed", e);
            }
        }
        return this;
    }

    @Override
    public DataStream<T> print() {
        return print("");
    }

    @Override
    public DataStream<T> print(String prefix) {
        String p = prefix == null ? "" : prefix;
        return addSink(v -> log.info("{}{}", p, v));
    }
}

