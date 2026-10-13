package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the window accumulator's checkpoint registration (operator-state
 * lifecycle contract, see docs/InMemory-Operator-State-Lifecycle-Design.md): each
 * terminal-op consumption registers its bucket map as a {@code window-N} snapshot source,
 * the per-record fence makes a mid-record checkpoint wait for the record's bucket updates,
 * and restore is FRESH by default — a restarted pipeline recomputes from its full-history
 * source instead of adopting restored buckets (which would double-count every window).
 */
class InMemoryWindowedStreamCheckpointTest {

    /** Tumbling assigner (size 10) whose default trigger is supplied by the test. */
    static final class TriggeredWindowAssigner<T> implements WindowAssigner<T> {
        private final java.util.function.Supplier<WindowAssigner.Trigger<T>> triggerFactory;

        TriggeredWindowAssigner(java.util.function.Supplier<WindowAssigner.Trigger<T>> triggerFactory) {
            this.triggerFactory = triggerFactory;
        }

        @Override
        public Iterable<Window> assignWindows(T element, long timestamp) {
            long start = (timestamp / 10) * 10;
            return List.of(new SimpleWindow(start, start + 10));
        }

        @Override
        public WindowAssigner.Trigger<T> getDefaultTrigger() {
            return triggerFactory.get();
        }
    }

    static final class SimpleWindow implements WindowAssigner.Window {
        private final long start;
        private final long end;

        SimpleWindow(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public long getStart() {
            return start;
        }

        @Override
        public long getEnd() {
            return end;
        }
    }

    private static List<InMemoryRecord<Integer>> records(int... values) {
        List<InMemoryRecord<Integer>> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new InMemoryRecord<>(values[i], (long) i));
        }
        return out;
    }

    /** Source that blocks between the first and second record, so a snapshot can be taken
     *  while exactly one record's worth of window state has been accumulated. */
    private static Iterator<InMemoryRecord<Integer>> blockingAfterFirst(List<InMemoryRecord<Integer>> data,
                                                                       CountDownLatch firstConsumed,
                                                                       CountDownLatch proceed) {
        return new Iterator<>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
                if (i == 1) {
                    firstConsumed.countDown();
                    try {
                        proceed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return i < data.size();
            }

            @Override
            public InMemoryRecord<Integer> next() {
                return data.get(i++);
            }
        };
    }

    private static WindowAssigner.Trigger<Integer> continuingTrigger() {
        return new WindowAssigner.Trigger<Integer>() {
            @Override
            public WindowAssigner.TriggerResult onElement(Integer e, long ts, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }

            @Override
            public WindowAssigner.TriggerResult onProcessingTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }

            @Override
            public WindowAssigner.TriggerResult onEventTime(long t, Window w) {
                return WindowAssigner.TriggerResult.CONTINUE;
            }
        };
    }

    private static String windowSourceName(Set<String> names) {
        return names.stream().filter(n -> n.startsWith("window-")).findFirst()
                .orElseThrow(() -> new AssertionError("no window snapshot source registered: " + names));
    }

    @SuppressWarnings("unchecked")
    private static List<InMemoryWindowedStream.BucketContent> windowPayload(Checkpoint checkpoint, String name) {
        return (List<InMemoryWindowedStream.BucketContent>) checkpoint.getStateSnapshot().getState(name);
    }

    @Test
    void checkpointCutContainsWindowBucketContents() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        List<InMemoryRecord<Integer>> data = records(1, 2, 3);
        CountDownLatch firstConsumed = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        InMemoryKeyedStream<String, Integer> stream = new InMemoryKeyedStream<>(
                () -> blockingAfterFirst(data, firstConsumed, proceed),
                v -> "k",
                null,
                coordinator);

        List<Long> out = new ArrayList<>();
        DataStream<Long> ds = stream
                .window(new TriggeredWindowAssigner<>(InMemoryWindowedStreamCheckpointTest::continuingTrigger))
                .count();

        Thread pipeline = new Thread(() -> ds.addSink(out::add));
        pipeline.setDaemon(true);
        pipeline.start();

        assertTrue(firstConsumed.await(5, TimeUnit.SECONDS),
                "the pipeline must consume the first record before the snapshot is triggered");

        long checkpointId = coordinator.triggerCheckpoint();
        String name = windowSourceName(coordinator.getRegisteredSourceNames());
        List<InMemoryWindowedStream.BucketContent> payload =
                windowPayload(coordinator.getCheckpoint(checkpointId), name);

        assertEquals(1, payload.size(), "the cut must contain the one bucket accumulated so far");
        InMemoryWindowedStream.BucketContent bucket = payload.get(0);
        assertEquals("k", bucket.key());
        assertEquals(0L, bucket.start());
        assertEquals(10L, bucket.end());
        assertEquals(0L, bucket.lastTimestamp());
        assertEquals(List.of(1L), bucket.contents());

        proceed.countDown();
        pipeline.join(5_000);
        assertFalse(pipeline.isAlive());
        assertEquals(List.of(3L), out, "the end-of-input flush must fire the full bucket");

        long secondId = coordinator.triggerCheckpoint();
        assertEquals(List.of(3L),
                windowPayload(coordinator.getCheckpoint(secondId), name).get(0).contents(),
                "a later cut must show the bucket's final contents");
    }

    @Test
    void restoreIsFreshByDefaultAndRestartRecomputesFully() {
        List<InMemoryRecord<Integer>> data = records(1, 2, 3);
        WindowAssigner<Integer> assigner = new TriggeredWindowAssigner<>(InMemoryWindowedStreamCheckpointTest::continuingTrigger);

        // First run: consume, then checkpoint — the cut carries the window's final contents.
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        List<Long> out1 = new ArrayList<>();
        new InMemoryKeyedStream<>(data::iterator, v -> "k", null, coordinator)
                .window(assigner)
                .count()
                .addSink(out1::add);
        assertEquals(List.of(3L), out1);
        long checkpointId = coordinator.triggerCheckpoint();
        String name = windowSourceName(coordinator.getRegisteredSourceNames());
        assertEquals(List.of(3L),
                windowPayload(coordinator.getCheckpoint(checkpointId), name).get(0).contents());

        // Restart: restore the cut, then run a second pipeline on the same coordinator. The
        // restored window state must never be injected into the live buckets — the in-memory
        // source replays its full history, so adopting restored buckets would double-count.
        coordinator.restoreFromCheckpoint(checkpointId);
        List<Long> out2 = new ArrayList<>();
        new InMemoryKeyedStream<>(data::iterator, v -> "k", null, coordinator)
                .window(assigner)
                .count()
                .addSink(out2::add);
        assertEquals(List.of(3L), out2,
                "a restarted pipeline must recompute the full count, not adopt the restored bucket");

        long secondId = coordinator.triggerCheckpoint();
        Set<String> names = coordinator.getRegisteredSourceNames();
        assertTrue(names.stream().filter(n -> n.startsWith("window-")).count() >= 2,
                "each consumption registers its own window source: " + names);
        for (String n : names) {
            if (n.startsWith("window-")) {
                assertEquals(List.of(3L),
                        windowPayload(coordinator.getCheckpoint(secondId), n).get(0).contents(),
                        "every window source in the cut must show the final contents");
            }
        }
    }

    @Test
    void windowRecordBodyBlocksAConcurrentCheckpointMidRecord() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        CountDownLatch insideRecord = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        InMemoryKeyedStream<String, Integer> stream = new InMemoryKeyedStream<>(
                records(1)::iterator,
                v -> "k",
                null,
                coordinator);

        List<Long> out = new ArrayList<>();
        DataStream<Long> ds = stream
                .window(new TriggeredWindowAssigner<>(() -> new WindowAssigner.Trigger<Integer>() {
                    @Override
                    public WindowAssigner.TriggerResult onElement(Integer e, long ts, Window w) {
                        insideRecord.countDown();
                        try {
                            proceed.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return WindowAssigner.TriggerResult.CONTINUE;
                    }

                    @Override
                    public WindowAssigner.TriggerResult onProcessingTime(long t, Window w) {
                        return WindowAssigner.TriggerResult.CONTINUE;
                    }

                    @Override
                    public WindowAssigner.TriggerResult onEventTime(long t, Window w) {
                        return WindowAssigner.TriggerResult.CONTINUE;
                    }
                }))
                .count();

        Thread pipeline = new Thread(() -> {
            try {
                ds.addSink(out::add);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        pipeline.setDaemon(true);
        pipeline.setUncaughtExceptionHandler((t, e) -> failure.set(e));
        pipeline.start();

        assertTrue(insideRecord.await(5, TimeUnit.SECONDS),
                "the pipeline must be inside the record before the snapshot is triggered");

        AtomicLong checkpointId = new AtomicLong();
        Thread snapshotter = new Thread(() -> {
            try {
                checkpointId.set(coordinator.triggerCheckpoint());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        snapshotter.setDaemon(true);
        snapshotter.start();

        // Without the per-record fence the snapshot would complete in microseconds; the fence
        // makes it wait for the record to finish its bucket updates.
        Thread.sleep(500);
        assertTrue(snapshotter.isAlive(),
                "the snapshot must block on the in-flight record instead of splitting its bucket updates");

        proceed.countDown();
        snapshotter.join(5_000);
        pipeline.join(5_000);
        assertFalse(snapshotter.isAlive(), "the snapshot must complete once the record finishes");
        assertFalse(pipeline.isAlive(), "the pipeline must drain once released");
        assertNull(failure.get(), "no thread may fail");
        assertEquals(List.of(1L), out);

        String name = windowSourceName(coordinator.getRegisteredSourceNames());
        assertEquals(List.of(1L),
                windowPayload(coordinator.getCheckpoint(checkpointId.get()), name).get(0).contents(),
                "the cut must contain the bucket the record accumulated");
    }
}
