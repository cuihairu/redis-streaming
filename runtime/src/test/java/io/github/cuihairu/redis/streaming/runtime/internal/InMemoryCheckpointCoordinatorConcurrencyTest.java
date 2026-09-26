package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression tests for B-22: the coordinator kept its registry/checkpoint maps in plain
 * non-synchronized {@code HashMap}/{@code LinkedHashMap}, so concurrent
 * {@code registerStore} + {@code triggerCheckpoint} raced (ConcurrentModificationException
 * while triggerCheckpoint iterated {@code storesById}), and {@code getRegisteredStores()}
 * exposed a live view whose iteration raced with registration.
 *
 * <p>All entry points are now guarded by a single monitor, and
 * {@code getRegisteredStores()} returns an immutable copy.
 */
class InMemoryCheckpointCoordinatorConcurrencyTest {

    private interface ConcurrentTask {
        void run(int threadIdx) throws Exception;
    }

    /** Runs {@code threads} tasks released simultaneously by a barrier; rethrows any worker failure. */
    private static void runConcurrently(int threads, ConcurrentTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    task.run(id);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRegistrationAndCheckpointingNeverThrowsOrLosesStores() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        int registrars = 8;
        int storesPerRegistrar = 200;
        int triggerThreads = 6;
        int triggersPerThread = 300;
        int expectedStores = registrars * storesPerRegistrar;
        AtomicInteger registered = new AtomicInteger();

        runConcurrently(registrars + triggerThreads, t -> {
            if (t < registrars) {
                for (int i = 0; i < storesPerRegistrar; i++) {
                    InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
                    store.put("state", "k-" + t + "-" + i, "v");
                    coordinator.registerStore(store);
                    registered.incrementAndGet();
                }
            } else {
                for (int i = 0; i < triggersPerThread; i++) {
                    coordinator.triggerCheckpoint();
                }
            }
        });

        assertEquals(expectedStores, registered.get());
        assertEquals(expectedStores, coordinator.getRegisteredStores().size(),
                "every registered store must be reachable afterwards");

        // A quiescent checkpoint must capture exactly the registered stores
        long finalId = coordinator.triggerCheckpoint();
        Checkpoint finalCp = coordinator.getCheckpoint(finalId);
        assertNotNull(finalCp);
        int snapshotted = 0;
        for (String key : finalCp.getStateSnapshot().getKeys()) {
            snapshotted++;
        }
        assertEquals(expectedStores, snapshotted,
                "final checkpoint must snapshot every registered store");
    }

    @Test
    void registeredStoresSnapshotIsStableWhileRegistering() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        int registrars = 4;
        int storesPerRegistrar = 60;
        List<Throwable> readerErrors = java.util.Collections.synchronizedList(new ArrayList<>());

        Thread reader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                while (System.nanoTime() < deadline) {
                    // iterate the returned map; must never see ConcurrentModificationException
                    for (String id : coordinator.getRegisteredStores().keySet()) {
                        assertNotNull(id);
                    }
                    if (coordinator.getRegisteredStores().size() >= registrars * storesPerRegistrar) {
                        return;
                    }
                    Thread.yield();
                }
                readerErrors.add(new AssertionError("reader did not observe full registration in time"));
            } catch (Throwable t) {
                readerErrors.add(t);
            }
        });
        reader.start();

        runConcurrently(registrars, t -> {
            for (int i = 0; i < storesPerRegistrar; i++) {
                coordinator.registerStore(new InMemoryKeyedStateStore<String>());
            }
        });
        reader.join(30_000);

        assertEquals(java.util.Collections.emptyList(), readerErrors,
                "iterating getRegisteredStores() must not fail while stores register");
        assertEquals(registrars * storesPerRegistrar, coordinator.getRegisteredStores().size());
    }

    @Test
    void checkpointSnapshotIsIsolatedFromLaterStateChanges() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        String storeId = coordinator.registerStore(store);
        store.put("state", "k1", "v1");

        long checkpointId = coordinator.triggerCheckpoint();

        // mutate the live store after the checkpoint: value replacement and new keys
        // must not leak into the completed snapshot
        store.put("state", "k1", "v2");
        store.put("state", "k2", "extra");

        Map<String, Map<Object, Object>> snap = coordinator.getCheckpoint(checkpointId)
                .getStateSnapshot().getState(storeId);
        assertEquals("v1", snap.get("state").get("k1"),
                "completed snapshot must keep the value captured at trigger time");
        assertNull(snap.get("state").get("k2"),
                "completed snapshot must not see keys added after trigger time");
    }
}
