package io.github.cuihairu.redis.streaming.source.generator;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers GeneratorSource#run including the delay branch and cancellation. */
class GeneratorSourceRunCoverageTest {

    private static StreamSource.SourceContext<Long> collecting(List<Long> out, AtomicBoolean stopped) {
        return new StreamSource.SourceContext<>() {
            @Override
            public void collect(Long element) {
                out.add(element);
            }
            @Override
            public void collectWithTimestamp(Long element, long timestamp) {
                out.add(element);
            }
            @Override
            public Object getCheckpointLock() {
                return this;
            }
            @Override
            public boolean isStopped() {
                return stopped.get();
            }
        };
    }

    @Test
    void runWithDelayEmitsAllElements() throws Exception {
        List<Long> out = new ArrayList<>();
        GeneratorSource<Long> source = new GeneratorSource<>(() -> 42L, 3, 1);
        source.run(collecting(out, new AtomicBoolean(false)));
        assertEquals(List.of(42L, 42L, 42L), out);
    }

    @Test
    void sequenceFactoryAndCancellation() throws Exception {
        List<Long> out = new ArrayList<>();
        GeneratorSource<Long> source = GeneratorSource.sequence(5);
        source.run(collecting(out, new AtomicBoolean(false)));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), out);

        List<Long> out2 = new ArrayList<>();
        GeneratorSource<Long> ranged = GeneratorSource.sequence(10, 2);
        ranged.run(collecting(out2, new AtomicBoolean(false)));
        assertEquals(List.of(10L, 11L), out2);

        GeneratorSource<Long> infinite = GeneratorSource.sequence(1);
        AtomicBoolean stopped = new AtomicBoolean(false);
        List<Long> out3 = new ArrayList<>();
        StreamSource.SourceContext<Long> ctx = collecting(out3, stopped);
        infinite.cancel();
        infinite.run(ctx);
        assertTrue(out3.isEmpty(), "cancelled source emits nothing");
    }
}
