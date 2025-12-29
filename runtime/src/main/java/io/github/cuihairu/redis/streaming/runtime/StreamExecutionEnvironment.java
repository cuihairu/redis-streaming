package io.github.cuihairu.redis.streaming.runtime;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryDataStream;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal, in-memory execution environment for the core streaming API.
 *
 * <p>This runtime is intentionally simple and single-threaded. Pipelines execute lazily and are
 * triggered by terminal operations like {@link DataStream#addSink} / {@link DataStream#print()}.
 */
public final class StreamExecutionEnvironment {

    private StreamExecutionEnvironment() {
    }

    public static StreamExecutionEnvironment getExecutionEnvironment() {
        return new StreamExecutionEnvironment();
    }

    public <T> DataStream<T> fromCollection(Collection<T> elements) {
        Objects.requireNonNull(elements, "elements");
        List<T> copy = new ArrayList<>(elements);
        return new InMemoryDataStream<>(copy::iterator);
    }

    @SafeVarargs
    public final <T> DataStream<T> fromElements(T... elements) {
        Objects.requireNonNull(elements, "elements");
        return fromCollection(Arrays.asList(elements));
    }

    public <T> DataStream<T> addSource(StreamSource<T> source) {
        Objects.requireNonNull(source, "source");

        List<InMemoryRecord<T>> out = new ArrayList<>();
        Object checkpointLock = new Object();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicLong fallbackTimestamp = new AtomicLong(0L);

        try {
            source.run(new StreamSource.SourceContext<>() {
                @Override
                public void collect(T element) {
                    out.add(new InMemoryRecord<>(element, fallbackTimestamp.getAndIncrement()));
                }

                @Override
                public void collectWithTimestamp(T element, long timestamp) {
                    out.add(new InMemoryRecord<>(element, timestamp));
                }

                @Override
                public Object getCheckpointLock() {
                    return checkpointLock;
                }

                @Override
                public boolean isStopped() {
                    return stopped.get();
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Source execution failed", e);
        } finally {
            stopped.set(true);
        }

        return InMemoryDataStream.fromRecords(out::iterator);
    }
}
