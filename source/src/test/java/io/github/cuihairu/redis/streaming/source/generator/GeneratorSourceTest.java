package io.github.cuihairu.redis.streaming.source.generator;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

