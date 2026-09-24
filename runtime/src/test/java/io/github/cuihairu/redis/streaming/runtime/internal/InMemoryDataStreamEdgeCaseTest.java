package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.TimestampAssigner;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers edge branches of {@link InMemoryDataStream}: exhausted {@code next()}, watermark
 * output idle/active hooks, sink failure wrapping and the record-supplier factory overloads.
 */
class InMemoryDataStreamEdgeCaseTest {

    @Test
    void emptyStreamsThrowNoSuchOnNextForAllOperatorIterators() {
        InMemoryDataStream<String> empty = new InMemoryDataStream<>(() -> Collections.<String>emptyIterator());
        assertEmptyIteratorThrows(empty);
        assertEmptyIteratorThrows(empty.map(String::toUpperCase));
        assertEmptyIteratorThrows(empty.filter(s -> true));
        assertEmptyIteratorThrows(empty.flatMap(s -> List.of(s)));
    }

    @Test
    void emptyWatermarkedStreamsThrowNoSuchOnNext() {
        InMemoryDataStream<String> empty = new InMemoryDataStream<>(() -> Collections.<String>emptyIterator());
        assertEmptyIteratorThrows(empty.assignTimestampsAndWatermarks(new NoopGenerator<>()));
        assertEmptyIteratorThrows(empty.assignTimestampsAndWatermarks(
                (TimestampAssigner<String>) (element, recordTimestamp) -> recordTimestamp,
                new NoopGenerator<>()));
    }

    @Test
    void watermarkOutputIdleAndActiveHooksAreInvoked() {
        List<String> events = new ArrayList<>();
        WatermarkGenerator<String> generator = new WatermarkGenerator<>() {
            @Override
            public void onEvent(String event, long eventTimestamp, WatermarkOutput output) {
                output.markIdle();
                output.markActive();
                output.emitWatermark(new Watermark(eventTimestamp));
            }

            @Override
            public void onPeriodicEmit(WatermarkOutput output) {
                output.markIdle();
                output.markActive();
            }
        };

        List<String> out = new ArrayList<>();
        InMemoryDataStream.fromRecords(
                        () -> List.<InMemoryRecord<String>>of(new InMemoryRecord<>("a", 5L)).iterator())
                .assignTimestampsAndWatermarks(generator)
                .addSink(out::add);
        assertEquals(List.of("a"), out);

        InMemoryDataStream.fromRecords(
                        () -> List.<InMemoryRecord<String>>of(new InMemoryRecord<>("b", 6L)).iterator())
                .assignTimestampsAndWatermarks((TimestampAssigner<String>) (e, ts) -> ts + 1, generator)
                .addSink(events::add);
        assertEquals(List.of("b"), events);
    }

    @Test
    void addSinkWrapsOpenFailure() {
        InMemoryDataStream<String> stream = new InMemoryDataStream<>(() -> List.of("a").iterator());
        RuntimeException e = assertThrows(RuntimeException.class, () -> stream.addSink(new StreamSink<>() {
            @Override
            public void open() throws Exception {
                throw new Exception("open boom");
            }

            @Override
            public void invoke(String value) {
            }
        }));
        assertTrue(e.getMessage().contains("Sink open failed"));
    }

    @Test
    void addSinkWrapsInvokeFailure() {
        InMemoryDataStream<String> stream = new InMemoryDataStream<>(() -> List.of("a").iterator());
        RuntimeException e = assertThrows(RuntimeException.class, () -> stream.addSink(new StreamSink<>() {
            @Override
            public void invoke(String value) throws Exception {
                throw new Exception("invoke boom");
            }
        }));
        assertTrue(e.getMessage().contains("Sink invocation failed"));
    }

    @Test
    void addSinkSwallowsCloseFailureAndKeepsResults() {
        List<String> seen = new ArrayList<>();
        InMemoryDataStream<String> stream = new InMemoryDataStream<>(() -> List.of("a", "b").iterator());
        DataStream<String> result = stream.addSink(new StreamSink<>() {
            @Override
            public void invoke(String value) {
                seen.add(value);
            }

            @Override
            public void close() throws Exception {
                throw new Exception("close boom");
            }
        });
        assertSame(stream, result);
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void fromRecordsWithCheckpointCoordinatorIsUsable() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        List<Integer> out = new ArrayList<>();
        InMemoryDataStream.fromRecords(
                        () -> List.<InMemoryRecord<Integer>>of(new InMemoryRecord<>(1, 0L)).iterator(),
                        coordinator)
                .map(v -> v + 1)
                .addSink(out::add);
        assertEquals(List.of(2), out);
    }

    private static void assertEmptyIteratorThrows(DataStream<?> stream) {
        Iterator<?> it = ((InMemoryDataStream<?>) stream).iterator();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    private static final class NoopGenerator<T> implements WatermarkGenerator<T> {
        @Override
        public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
        }
    }
}
