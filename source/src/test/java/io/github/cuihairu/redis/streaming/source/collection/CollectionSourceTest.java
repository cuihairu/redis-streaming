package io.github.cuihairu.redis.streaming.source.collection;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionSourceTest {

    @Test
    void testCollectionSource() throws Exception {
        List<String> input = Arrays.asList("a", "b", "c", "d", "e");
        CollectionSource<String> source = new CollectionSource<>(input);

        TestSourceContext<String> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(5, ctx.getCollected().size());
        assertEquals(input, ctx.getCollected());
    }

    @Test
    void testEmptyCollection() throws Exception {
        CollectionSource<String> source = new CollectionSource<>(Arrays.asList());

        TestSourceContext<String> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertTrue(ctx.getCollected().isEmpty());
    }

    @Test
    void testCancel() {
        CollectionSource<Integer> source = new CollectionSource<>(Arrays.asList(1, 2, 3));

        // Test that cancel() can be called without errors
        assertDoesNotThrow(() -> source.cancel());
    }

    /**
     * Test implementation of SourceContext
     */
    private static class TestSourceContext<T> implements StreamSource.SourceContext<T> {
        private final List<T> collected = new ArrayList<>();
        private final Object lock = new Object();
        private boolean stopped = false;

        @Override
        public void collect(T element) {
            collected.add(element);
        }

        @Override
        public void collectWithTimestamp(T element, long timestamp) {
            collected.add(element);
        }

        @Override
        public Object getCheckpointLock() {
            return lock;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        public List<T> getCollected() {
            return collected;
        }

        public void stop() {
            stopped = true;
        }
    }
}
