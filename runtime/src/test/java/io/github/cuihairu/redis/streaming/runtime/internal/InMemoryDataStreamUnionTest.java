package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDataStreamUnionTest {

    @Test
    void unionMergesTwoStreamsRoundRobin() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1, 2, 3).iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.of(10, 20, 30).iterator());

        List<Integer> out = new ArrayList<>();
        a.union(b).addSink(out::add);

        assertEquals(List.of(1, 10, 2, 20, 3, 30), out);
    }

    @Test
    void unionWithUnevenStreamsDrainsBoth() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1).iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.of(10, 20, 30).iterator());

        List<Integer> out = new ArrayList<>();
        a.union(b).addSink(out::add);

        assertEquals(List.of(1, 10, 20, 30), out);
    }

    @Test
    void unionWithEmptyLeftDrainsRight() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.<Integer>of().iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.of(1, 2).iterator());

        List<Integer> out = new ArrayList<>();
        a.union(b).addSink(out::add);

        assertEquals(List.of(1, 2), out);
    }

    @Test
    void unionWithEmptyRightDrainsLeft() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1).iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.<Integer>of().iterator());

        List<Integer> out = new ArrayList<>();
        a.union(b).addSink(out::add);

        assertEquals(List.of(1), out);
    }

    @Test
    void unionIsPerConsumptionReusable() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1, 2).iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.of(10, 20).iterator());

        DataStream<Integer> unioned = a.union(b);

        List<Integer> out1 = new ArrayList<>();
        unioned.addSink(out1::add);

        List<Integer> out2 = new ArrayList<>();
        unioned.addSink(out2::add);

        assertEquals(List.of(1, 10, 2, 20), out1);
        assertEquals(List.of(1, 10, 2, 20), out2);
    }

    @Test
    void unionComposesWithTransforms() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1, 2).iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.of(3).iterator());

        List<Integer> out = new ArrayList<>();
        a.union(b).map(v -> v * 10).filter(v -> v > 15).addSink(out::add);

        assertEquals(List.of(30, 20), out);
    }

    @Test
    void unionWithSubtypeWidensToCommonType() {
        InMemoryDataStream<Number> a = InMemoryDataStream.fromRecords(
                () -> List.<InMemoryRecord<Number>>of(
                        new InMemoryRecord<>(1, 0L), new InMemoryRecord<>(2, 1L)).iterator());
        InMemoryDataStream<Integer> b = new InMemoryDataStream<>(() -> List.of(3).iterator());

        List<Number> out = new ArrayList<>();
        a.union(b).addSink(out::add);

        assertEquals(List.of(1, 3, 2), out);
    }

    @Test
    void unionPreservesTimestampsFromEachSource() {
        InMemoryDataStream<Integer> a = InMemoryDataStream.fromRecords(
                () -> List.of(new InMemoryRecord<>(1, 100L), new InMemoryRecord<>(2, 200L)).iterator());
        InMemoryDataStream<Integer> b = InMemoryDataStream.fromRecords(
                () -> List.of(new InMemoryRecord<>(10, 300L), new InMemoryRecord<>(20, 400L)).iterator());

        @SuppressWarnings("unchecked")
        InMemoryDataStream<Integer> unioned = (InMemoryDataStream<Integer>) a.union(b);

        List<Long> timestamps = new ArrayList<>();
        var it = unioned.recordSupplier().get();
        while (it.hasNext()) {
            timestamps.add(it.next().timestamp());
        }

        assertEquals(List.of(100L, 300L, 200L, 400L), timestamps);
    }

    @Test
    void unionNullOtherThrows() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1).iterator());

        assertThrows(NullPointerException.class, () -> a.union(null));
    }

    @Test
    void unionWithNonInMemoryStreamThrowsUnsupported() {
        InMemoryDataStream<Integer> a = new InMemoryDataStream<>(() -> List.of(1).iterator());

        DataStream<Integer> notInMemory = new DataStream<>() {
            @Override
            public <R> DataStream<R> map(Function<Integer, R> mapper) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public DataStream<Integer> filter(Predicate<Integer> predicate) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public <R> DataStream<R> flatMap(Function<Integer, Iterable<R>> mapper) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public <K> KeyedStream<K, Integer> keyBy(Function<Integer, K> keySelector) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public DataStream<Integer> addSink(StreamSink<Integer> sink) {
                throw new UnsupportedOperationException("not needed");
            }

            @Override
            public DataStream<Integer> print() {
                return this;
            }

            @Override
            public DataStream<Integer> print(String prefix) {
                return this;
            }
        };

        assertThrows(UnsupportedOperationException.class, () -> a.union(notInMemory));
    }
}
