package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the P1 checkpoint barrier: a snapshot used to race the pipeline
 * thread's plain-{@code HashMap} state writes (ConcurrentModificationException / torn
 * copies) because nothing synchronized mutations against snapshots. Registered stores now
 * mutate and snapshot under the coordinator's checkpoint lock, and the coordinator holds it
 * across all stores' snapshots, so one checkpoint is a single consistent cut.
 *
 * <p>Deliberately not asserted: atomicity of one record's multiple writes (two separate
 * {@code put} calls can still be split by a snapshot — the per-record barrier is the
 * checkpoint-protocol work this P1 does not claim).</p>
 */
class InMemoryCheckpointBarrierTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Map<Object, Object>> storeSnapshot(Checkpoint checkpoint, String storeId) {
        return (Map<String, Map<Object, Object>>) checkpoint.getStateSnapshot().getState(storeId);
    }

    @Test
    void snapshotWaitsForAnInFlightStateWriteInsteadOfRacingIt() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        String storeId = coordinator.registerStore(store);

        CountDownLatch writerInsideBarrier = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            // Holding the barrier while writing: the concurrent snapshot must wait and see the
            // write, not a torn pre-write state.
            synchronized (coordinator.checkpointLock()) {
                writerInsideBarrier.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                store.put("counts", "k", 42);
            }
        });
        writer.setUncaughtExceptionHandler((t, e) -> failure.set(e));
        writer.start();
        assertTrue(writerInsideBarrier.await(5, TimeUnit.SECONDS),
                "writer must be inside the barrier before the snapshot is triggered");

        long start = System.nanoTime();
        long checkpointId = coordinator.triggerCheckpoint();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        writer.join(5_000);
        assertFalse(writer.isAlive());
        assertEquals(42, storeSnapshot(coordinator.getCheckpoint(checkpointId), storeId).get("counts").get("k"),
                "the snapshot must include the write that was in flight when it started");
        assertTrue(elapsedMs >= 100,
                "snapshot must wait for the in-flight write instead of racing it, took " + elapsedMs + "ms");
    }

    @Test
    void concurrentWritesAndSnapshotsNeverCorruptTheStore() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        InMemoryKeyedStateStore<String> storeA = new InMemoryKeyedStateStore<>();
        InMemoryKeyedStateStore<String> storeB = new InMemoryKeyedStateStore<>();
        coordinator.registerStore(storeA);
        coordinator.registerStore(storeB);

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong snapshots = new AtomicLong();

        Thread writer = new Thread(() -> {
            long v = 0;
            try {
                while (!stop.get()) {
                    String key = "k-" + (v % 8);
                    storeA.put("s", key, v);
                    storeB.put("s", key, v);
                    v++;
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread snapshotter = new Thread(() -> {
            try {
                while (!stop.get() && snapshots.get() < 200) {
                    long id = coordinator.triggerCheckpoint();
                    Checkpoint checkpoint = coordinator.getCheckpoint(id);
                    for (Map<String, Map<Object, Object>> store : List.of(
                            storeSnapshot(checkpoint, "store-1"), storeSnapshot(checkpoint, "store-2"))) {
                        // Each copied state map must be well-formed: known keys, Long values —
                        // a snapshot that raced a resize would throw or return garbage.
                        for (Map.Entry<String, Map<Object, Object>> state : store.entrySet()) {
                            assertEquals("s", state.getKey());
                            assertEquals(8, state.getValue().size());
                            for (int k = 0; k < 8; k++) {
                                Object value = state.getValue().get("k-" + k);
                                assertTrue(value instanceof Long, "corrupt value " + value);
                            }
                        }
                    }
                    snapshots.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        writer.start();
        snapshotter.start();
        snapshotter.join(30_000);
        stop.set(true);
        writer.join(5_000);

        assertFalse(writer.isAlive(), "writer must finish once stopped");
        assertTrue(snapshots.get() > 0, "the stress must actually take checkpoints");
        assertNull(failure.get(), "no thread may observe a torn snapshot");
    }

    @Test
    void checkpointsRunConcurrentlyWithALivePipelineWithoutCorruptingIt() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing();
        int size = 20_000;

        StreamSource<Integer> source = ctx -> {
            for (int i = 0; i < size; i++) {
                ctx.collect(i);
            }
        };
        List<Integer> out = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // keyBy registers a state store whose setCurrentKey runs per record on the pipeline
        // thread, so every record contends the checkpoint barrier with the snapshots below.
        Thread pipeline = new Thread(() -> {
            try {
                env.addSource(source).keyBy(v -> "k").reduce((a, b) -> b).addSink(out::add);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        pipeline.start();

        long checkpoints = 0;
        while (pipeline.isAlive() && checkpoints < 200) {
            env.getCheckpointCoordinator().triggerCheckpoint();
            checkpoints++;
            Thread.sleep(1);
        }
        pipeline.join(30_000);

        assertFalse(pipeline.isAlive(), "the pipeline must drain while checkpoints run beside it");
        assertEquals(size, out.size(), "no record may be lost while checkpoints interleave");
        assertNull(failure.get(), "the pipeline must not fail under concurrent checkpoints");
        assertTrue(checkpoints > 0, "the stress must actually take checkpoints");
    }
}
