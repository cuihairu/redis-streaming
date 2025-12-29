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

    private final Supplier<Iterator<InMemoryRecord<T>>> recordIteratorSupplier;

    public InMemoryDataStream(Supplier<Iterator<T>> iteratorSupplier) {
        Objects.requireNonNull(iteratorSupplier, "iteratorSupplier");
        this.recordIteratorSupplier = () -> new Iterator<>() {
            private final Iterator<T> it = iteratorSupplier.get();
            private long timestamp = 0L;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public InMemoryRecord<T> next() {
                return new InMemoryRecord<>(it.next(), timestamp++);
            }
        };
    }

    private InMemoryDataStream(Supplier<Iterator<InMemoryRecord<T>>> recordIteratorSupplier, boolean unused) {
        this.recordIteratorSupplier = Objects.requireNonNull(recordIteratorSupplier, "recordIteratorSupplier");
    }

    public static <T> InMemoryDataStream<T> fromRecords(Supplier<Iterator<InMemoryRecord<T>>> recordIteratorSupplier) {
        return new InMemoryDataStream<>(recordIteratorSupplier, true);
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private final Iterator<InMemoryRecord<T>> it = recordIteratorSupplier.get();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public T next() {
                return it.next().value();
            }
        };
    }

    @Override
    public <R> DataStream<R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private final Iterator<InMemoryRecord<T>> it = recordIteratorSupplier.get();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public InMemoryRecord<R> next() {
                InMemoryRecord<T> record = it.next();
                return new InMemoryRecord<>(mapper.apply(record.value()), record.timestamp());
            }
        });
    }

    @Override
    public DataStream<T> filter(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private final Iterator<InMemoryRecord<T>> it = recordIteratorSupplier.get();
            private boolean computed = false;
            private boolean hasNext = false;
            private InMemoryRecord<T> next;

            @Override
            public boolean hasNext() {
                if (computed) {
                    return hasNext;
                }
                computed = true;
                while (it.hasNext()) {
                    InMemoryRecord<T> candidate = it.next();
                    if (predicate.test(candidate.value())) {
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
            public InMemoryRecord<T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                computed = false;
                InMemoryRecord<T> out = next;
                next = null;
                return out;
            }
        });
    }

    @Override
    public <R> DataStream<R> flatMap(Function<T, Iterable<R>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return InMemoryDataStream.fromRecords(() -> new Iterator<>() {
            private final Iterator<InMemoryRecord<T>> it = recordIteratorSupplier.get();
            private Iterator<R> current = Collections.emptyIterator();
            private long currentTimestamp = 0L;

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && it.hasNext()) {
                    InMemoryRecord<T> record = it.next();
                    currentTimestamp = record.timestamp();
                    Iterable<R> mapped = mapper.apply(record.value());
                    current = mapped == null ? Collections.emptyIterator() : mapped.iterator();
                }
                return current.hasNext();
            }

            @Override
            public InMemoryRecord<R> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return new InMemoryRecord<>(current.next(), currentTimestamp);
            }
        });
    }

    @Override
    public <K> KeyedStream<K, T> keyBy(Function<T, K> keySelector) {
        Objects.requireNonNull(keySelector, "keySelector");
        return new InMemoryKeyedStream<>(recordIteratorSupplier, keySelector, true);
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
