package io.github.cuihairu.redis.streaming.runtime;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link StreamExecutionEnvironment#addSource(StreamSource)} failure paths and the
 * {@code SourceContext} lock/stop accessors.
 */
class StreamExecutionEnvironmentBranchTest {

    @Test
    void addSourceWrapsOpenFailure() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamSource<Integer> source = new StreamSource<>() {
            @Override
            public void open() throws Exception {
                throw new Exception("open boom");
            }

            @Override
            public void run(SourceContext<Integer> ctx) {
            }
        };

        RuntimeException e = assertThrows(RuntimeException.class, () -> env.addSource(source));
        assertTrue(e.getMessage().contains("Source open failed"));
    }

    @Test
    void addSourceSwallowsCloseFailureAndKeepsCollectedElements() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamSource<Integer> source = new StreamSource<>() {
            @Override
            public void run(SourceContext<Integer> ctx) {
                ctx.collect(1);
                ctx.collect(2);
            }

            @Override
            public void close() throws Exception {
                throw new Exception("close boom");
            }
        };

        List<Integer> out = new ArrayList<>();
        env.addSource(source).addSink(out::add);
        assertEquals(List.of(1, 2), out);
    }

    @Test
    void sourceContextExposesCheckpointLockAndStopFlag() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        Object[] observed = new Object[4];
        StreamSource<Integer> source = ctx -> {
            observed[0] = ctx.getCheckpointLock();
            observed[1] = ctx.isStopped();
            ctx.collect(7);
            observed[2] = ctx.getCheckpointLock();
            observed[3] = ctx.isStopped();
        };

        List<Integer> out = new ArrayList<>();
        env.addSource(source).addSink(out::add);

        assertEquals(List.of(7), out);
        assertNotNull(observed[0]);
        assertSame(observed[0], observed[2]);
        assertEquals(Boolean.FALSE, observed[1]);
        assertEquals(Boolean.FALSE, observed[3]);
    }
}
