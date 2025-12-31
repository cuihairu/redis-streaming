package io.github.cuihairu.redis.streaming.source.generator;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorSourceTest {

    @Test
    void sequenceGeneratesFromZero() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(3);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of(0L, 1L, 2L), out);
    }

    @Test
    void sequenceGeneratesFromStart() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(10, 3);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of(10L, 11L, 12L), out);
    }

    @Test
    void cancelStopsGenerationEarly() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        GeneratorSource<Integer> source = new GeneratorSource<>(counter::getAndIncrement, 100);

        List<Integer> out = new ArrayList<>();
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(Integer element) {
                out.add(element);
                if (out.size() == 2) {
                    source.cancel();
                }
            }

            @Override
            public void collectWithTimestamp(Integer element, long timestamp) {
                collect(element);
            }

            @Override
            public Object getCheckpointLock() {
                return this;
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        });

        assertEquals(List.of(0, 1), out);
    }

    @Test
    void sequenceWithZeroCount() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(0);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertTrue(out.isEmpty());
    }

    @Test
    void sequenceWithNegativeCount() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(-1);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertTrue(out.isEmpty());
    }

    @Test
    void sequenceWithCountOfOne() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(1);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of(0L), out);
    }

    @Test
    void sequenceWithLargeCount() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(100);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(100, out.size());
        assertEquals(0L, out.get(0));
        assertEquals(99L, out.get(99));
    }

    @Test
    void customGeneratorFunction() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        GeneratorSource<String> source = new GeneratorSource<>(
                () -> "item-" + counter.getAndIncrement(), 3
        );

        List<String> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of("item-0", "item-1", "item-2"), out);
    }

    @Test
    void generatorWithNullFunction() throws Exception {
        // Null supplier will cause NullPointerException when run is called
        GeneratorSource<Integer> source = new GeneratorSource<>(null, 5);

        List<Integer> out = new ArrayList<>();

        assertThrows(NullPointerException.class, () -> {
            source.run(new CollectingContext<>(out));
        });
    }

    @Test
    void collectWithTimestamp() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(2);

        List<Long> timestamps = new ArrayList<>();
        List<Long> values = new ArrayList<>();

        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(Long element) {
                values.add(element);
            }

            @Override
            public void collectWithTimestamp(Long element, long timestamp) {
                values.add(element);
                timestamps.add(timestamp);
            }

            @Override
            public Object getCheckpointLock() {
                return this;
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        });

        // collectWithTimestamp is not called by GeneratorSource, only collect()
        assertEquals(2, values.size());
        assertTrue(timestamps.isEmpty());
    }

    @Test
    void generatorReturnsSameObject() throws Exception {
        String fixed = "fixed-value";
        GeneratorSource<String> source = new GeneratorSource<>(() -> fixed, 3);

        List<String> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of(fixed, fixed, fixed), out);
    }

    @Test
    void sequenceFromStartWithCount() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(5, 3);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of(5L, 6L, 7L), out);
    }

    @Test
    void sequenceFromNegativeStart() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(-5, 3);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of(-5L, -4L, -3L), out);
    }

    @Test
    void sequenceWithZeroCountFromStart() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(10, 0);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertTrue(out.isEmpty());
    }

    @Test
    void generatorWithSideEffect() throws Exception {
        AtomicInteger sideEffect = new AtomicInteger(0);
        GeneratorSource<Integer> source = new GeneratorSource<>(() -> {
            sideEffect.incrementAndGet();
            return sideEffect.get();
        }, 5);

        List<Integer> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(5, out.size());
        assertEquals(5, sideEffect.get());
    }

    @Test
    void multipleCancels() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(100);

        List<Long> out = new ArrayList<>();
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(Long element) {
                out.add(element);
                if (out.size() == 3) {
                    source.cancel();
                    source.cancel(); // Multiple cancels should be safe
                }
            }

            @Override
            public void collectWithTimestamp(Long element, long timestamp) {
                collect(element);
            }

            @Override
            public Object getCheckpointLock() {
                return this;
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        });

        assertEquals(3, out.size());
    }

    @Test
    void checkpointLockNotNull() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(1);

        List<Object> locks = new ArrayList<>();
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(Long element) {
                locks.add(getCheckpointLock());
            }

            @Override
            public void collectWithTimestamp(Long element, long timestamp) {
            }

            @Override
            public Object getCheckpointLock() {
                return new Object();
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        });

        assertFalse(locks.isEmpty());
        assertNotNull(locks.get(0));
    }

    @Test
    void sequenceWithCustomStartAndLargeCount() throws Exception {
        GeneratorSource<Long> source = GeneratorSource.sequence(1000, 10);

        List<Long> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(10, out.size());
        assertEquals(1000L, out.get(0));
        assertEquals(1009L, out.get(9));
    }

    @Test
    void generatorWithNullReturnValue() throws Exception {
        GeneratorSource<String> source = new GeneratorSource<>(() -> null, 3);

        List<String> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(3, out.size());
        assertNull(out.get(0));
        assertNull(out.get(1));
        assertNull(out.get(2));
    }

    private static final class CollectingContext<T> implements StreamSource.SourceContext<T> {
        private final List<T> out;

        private CollectingContext(List<T> out) {
            this.out = out;
        }

        @Override
        public void collect(T element) {
            out.add(element);
        }

        @Override
        public void collectWithTimestamp(T element, long timestamp) {
            out.add(element);
        }

        @Override
        public Object getCheckpointLock() {
            return this;
        }

        @Override
        public boolean isStopped() {
            return false;
        }
    }
}

