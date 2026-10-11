package io.github.cuihairu.redis.streaming.runtime;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import io.github.cuihairu.redis.streaming.api.checkpoint.CheckpointCoordinator;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryDataStream;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A minimal, in-memory execution environment for the core streaming API.
 *
 * <p>Pipelines execute lazily and are triggered by terminal operations like
 * {@link DataStream#addSink} / {@link DataStream#print()}. {@link #addSource} streams records
 * through a bounded hand-off queue instead of collecting the whole source into memory, so
 * unbounded sources do not materialize ({@link #QUEUE_CAPACITY} records at most in flight).</p>
 */
public final class StreamExecutionEnvironment {

    private static final Logger log = LoggerFactory.getLogger(StreamExecutionEnvironment.class);

    /** Records at most in flight between a source and its terminal operation. */
    static final int QUEUE_CAPACITY = 256;

    /** Sentinel queued after the source finished (or failed) so the consumer terminates. */
    private static final Object END_OF_SOURCE = new Object();

    private static final long START_WAIT_STEP_MILLIS = 20;

    private InMemoryCheckpointCoordinator checkpointCoordinator;

    private StreamExecutionEnvironment() {
    }

    public static StreamExecutionEnvironment getExecutionEnvironment() {
        return new StreamExecutionEnvironment();
    }

    public StreamExecutionEnvironment enableCheckpointing() {
        if (checkpointCoordinator == null) {
            checkpointCoordinator = new InMemoryCheckpointCoordinator();
        }
        return this;
    }

    public CheckpointCoordinator getCheckpointCoordinator() {
        return checkpointCoordinator;
    }

    public <T> DataStream<T> fromCollection(Collection<T> elements) {
        Objects.requireNonNull(elements, "elements");
        List<T> copy = new ArrayList<>(elements);
        return InMemoryDataStream.fromRecords(() -> new java.util.Iterator<>() {
            private final java.util.Iterator<T> iterator = copy.iterator();
            private long timestamp = 0L;

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public InMemoryRecord<T> next() {
                return new InMemoryRecord<>(iterator.next(), timestamp++);
            }
        }, checkpointCoordinator);
    }

    @SafeVarargs
    public final <T> DataStream<T> fromElements(T... elements) {
        Objects.requireNonNull(elements, "elements");
        return fromCollection(Arrays.asList(elements));
    }

    public <T> DataStream<T> addSource(StreamSource<T> source) {
        Objects.requireNonNull(source, "source");

        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        Object checkpointLock = new Object();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean produced = new AtomicBoolean(false);
        AtomicLong fallbackTimestamp = new AtomicLong(0L);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            source.open();
        } catch (Exception e) {
            throw new RuntimeException("Source open failed", e);
        }

        // The source runs on its own daemon thread: collect() pushes into the bounded queue and the
        // terminal operation drains it, so the pipeline stays streaming instead of holding the whole
        // input. Daemon so an abandoned iteration (a manual Iterator that stops early) cannot keep
        // the JVM alive.
        Thread producer = new Thread(() -> {
            try {
                source.run(new StreamSource.SourceContext<>() {
                    @Override
                    public void collect(T element) {
                        emit(element, fallbackTimestamp.getAndIncrement(), queue, checkpointLock, produced);
                    }

                    @Override
                    public void collectWithTimestamp(T element, long timestamp) {
                        emit(element, timestamp, queue, checkpointLock, produced);
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
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                stopped.set(true);
                try {
                    source.close();
                } catch (Exception e) {
                    log.warn("Failed to close source", e);
                }
                if (failure.get() != null) {
                    // Records collected before the failure are dropped, as in the previous eager
                    // model: the consumer rethrows from the sentinel instead of seeing them.
                    queue.clear();
                }
                // The consumer only terminates through the sentinel, so keep offering until it
                // lands (put blocks until the queue drained). The interrupt flag is intentionally
                // not restored: rethrowing here would skip the sentinel and strand the consumer.
                while (true) {
                    try {
                        queue.put(END_OF_SOURCE);
                        break;
                    } catch (InterruptedException ie) {
                        // retry
                    }
                }
            }
        }, "redis-streaming-source");
        producer.setDaemon(true);

        try {
            producer.start();
            while (producer.isAlive() && !produced.get() && failure.get() == null) {
                // A bounded source may run to completion before the terminal operation starts
                // draining; wait for it so immediate failures still surface from addSource.
                producer.join(START_WAIT_STEP_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            producer.interrupt();
            throw new RuntimeException("Interrupted while starting source", e);
        }

        Throwable startFailure = failure.get();
        if (startFailure != null) {
            throw new RuntimeException("Source execution failed", startFailure);
        }

        return InMemoryDataStream.fromRecords(() -> new Iterator<InMemoryRecord<T>>() {
            private boolean exhausted = false;
            private InMemoryRecord<T> next;

            @Override
            public boolean hasNext() {
                if (exhausted) {
                    return false;
                }
                if (next != null) {
                    return true;
                }
                while (true) {
                    Object item;
                    try {
                        item = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for source records", e);
                    }
                    if (item == END_OF_SOURCE) {
                        exhausted = true;
                        Throwable t = failure.get();
                        if (t != null) {
                            throw new RuntimeException("Source execution failed", t);
                        }
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    InMemoryRecord<T> record = (InMemoryRecord<T>) item;
                    next = record;
                    return true;
                }
            }

            @Override
            public InMemoryRecord<T> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                InMemoryRecord<T> out = next;
                next = null;
                return out;
            }
        }, checkpointCoordinator);
    }

    private <T> void emit(T element, long timestamp, ArrayBlockingQueue<Object> queue,
                         Object checkpointLock, AtomicBoolean produced) {
        try {
            // Emission holds the checkpoint lock: this is the seam a checkpoint barrier will
            // synchronize on (docs/todo: SourceContext.getCheckpointLock). The consumer never
            // takes this lock, so backpressure can never deadlock it.
            synchronized (checkpointLock) {
                queue.put(new InMemoryRecord<>(element, timestamp));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while emitting source record", e);
        }
        produced.set(true);
    }
}
