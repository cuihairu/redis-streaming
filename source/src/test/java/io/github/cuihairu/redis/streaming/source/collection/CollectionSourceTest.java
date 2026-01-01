package io.github.cuihairu.redis.streaming.source.collection;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

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

    @Test
    void testSingleElement() throws Exception {
        CollectionSource<Integer> source = new CollectionSource<>(Arrays.asList(42));

        TestSourceContext<Integer> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(1, ctx.getCollected().size());
        assertEquals(42, ctx.getCollected().get(0));
    }

    @Test
    void testNullElements() throws Exception {
        CollectionSource<String> source = new CollectionSource<>(Arrays.asList("a", null, "b", null));

        TestSourceContext<String> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(4, ctx.getCollected().size());
        assertEquals("a", ctx.getCollected().get(0));
        assertNull(ctx.getCollected().get(1));
        assertEquals("b", ctx.getCollected().get(2));
        assertNull(ctx.getCollected().get(3));
    }

    @Test
    void testWithLinkedList() throws Exception {
        LinkedList<Integer> input = new LinkedList<>(Arrays.asList(1, 2, 3));
        CollectionSource<Integer> source = new CollectionSource<>(input);

        TestSourceContext<Integer> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(3, ctx.getCollected().size());
        assertEquals(Arrays.asList(1, 2, 3), ctx.getCollected());
    }

    @Test
    void testWithHashSet() throws Exception {
        Set<String> input = new HashSet<>(Arrays.asList("x", "y", "z"));
        CollectionSource<String> source = new CollectionSource<>(input);

        TestSourceContext<String> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(3, ctx.getCollected().size());
        assertTrue(ctx.getCollected().containsAll(input));
    }

    @Test
    void testWithUnmodifiableCollection() throws Exception {
        Collection<String> input = Collections.unmodifiableList(Arrays.asList("a", "b", "c"));
        CollectionSource<String> source = new CollectionSource<>(input);

        TestSourceContext<String> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(3, ctx.getCollected().size());
        assertTrue(ctx.getCollected().containsAll(input));
    }

    @Test
    void testWithLargeCollection() throws Exception {
        List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            input.add(i);
        }

        CollectionSource<Integer> source = new CollectionSource<>(input);
        TestSourceContext<Integer> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(1000, ctx.getCollected().size());
        assertEquals(0, ctx.getCollected().get(0));
        assertEquals(999, ctx.getCollected().get(999));
    }

    @Test
    void testCancelDuringRun() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        CollectionSource<Integer> source = new CollectionSource<>(input);

        TestSourceContext<Integer> ctx = new TestSourceContext<Integer>() {
            private int count = 0;

            @Override
            public void collect(Integer element) {
                super.collect(element);
                count++;
                if (count == 3) {
                    source.cancel();
                }
            }
        };

        source.run(ctx);

        // Should stop after 3 elements due to cancel
        assertEquals(3, ctx.getCollected().size());
        assertEquals(Arrays.asList(1, 2, 3), ctx.getCollected());
    }

    @Test
    void testMultipleRuns() throws Exception {
        CollectionSource<String> source = new CollectionSource<>(Arrays.asList("a", "b"));

        TestSourceContext<String> ctx1 = new TestSourceContext<>();
        source.run(ctx1);
        assertEquals(Arrays.asList("a", "b"), ctx1.getCollected());

        // Run again - should work fine
        TestSourceContext<String> ctx2 = new TestSourceContext<>();
        source.run(ctx2);
        assertEquals(Arrays.asList("a", "b"), ctx2.getCollected());
    }

    @Test
    void testWithDuplicateElements() throws Exception {
        CollectionSource<String> source = new CollectionSource<>(
                Arrays.asList("a", "a", "b", "a", "b", "b")
        );

        TestSourceContext<String> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(6, ctx.getCollected().size());
        assertEquals(Arrays.asList("a", "a", "b", "a", "b", "b"), ctx.getCollected());
    }

    @Test
    void testCollectWithTimestamp() throws Exception {
        CollectionSource<Long> source = new CollectionSource<>(Arrays.asList(1L, 2L, 3L));

        List<Long> values = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        StreamSource.SourceContext<Long> ctx = new StreamSource.SourceContext<>() {
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
        };

        source.run(ctx);

        // collectWithTimestamp is not called by CollectionSource, only collect()
        assertEquals(3, values.size());
        assertTrue(timestamps.isEmpty());
    }

    @Test
    void testWithIntegerMaxValue() throws Exception {
        CollectionSource<Integer> source = new CollectionSource<>(
                Arrays.asList(Integer.MAX_VALUE, Integer.MIN_VALUE, 0)
        );

        TestSourceContext<Integer> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(3, ctx.getCollected().size());
        assertEquals(Integer.MAX_VALUE, ctx.getCollected().get(0));
        assertEquals(Integer.MIN_VALUE, ctx.getCollected().get(1));
        assertEquals(0, ctx.getCollected().get(2));
    }

    @Test
    void testWithLongValues() throws Exception {
        CollectionSource<Long> source = new CollectionSource<>(
                Arrays.asList(Long.MAX_VALUE, Long.MIN_VALUE, 0L, -1L, 1L)
        );

        TestSourceContext<Long> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(5, ctx.getCollected().size());
        assertEquals(Long.MAX_VALUE, ctx.getCollected().get(0));
        assertEquals(Long.MIN_VALUE, ctx.getCollected().get(1));
    }

    @Test
    void testWithDoubleValues() throws Exception {
        CollectionSource<Double> source = new CollectionSource<>(
                Arrays.asList(1.1, 2.2, Double.MAX_VALUE, Double.MIN_VALUE, 0.0)
        );

        TestSourceContext<Double> ctx = new TestSourceContext<>();
        source.run(ctx);

        assertEquals(5, ctx.getCollected().size());
        assertEquals(1.1, ctx.getCollected().get(0), 0.001);
        assertEquals(2.2, ctx.getCollected().get(1), 0.001);
    }

    @Test
    void testIsSerializable() {
        CollectionSource<String> source = new CollectionSource<>(Arrays.asList("a", "b", "c"));
        assertTrue(source instanceof java.io.Serializable);
    }

    @Test
    void testCancelMultipleTimes() {
        CollectionSource<Integer> source = new CollectionSource<>(Arrays.asList(1, 2, 3));

        assertDoesNotThrow(() -> {
            source.cancel();
            source.cancel();
            source.cancel();
        });
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
