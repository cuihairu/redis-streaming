package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.checkpoint.CheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-memory checkpoint coordinator for the in-memory runtime.
 *
 * <p>This implementation is single-process and snapshots registered keyed-state stores
 * synchronously when {@link #triggerCheckpoint()} is called.</p>
 *
 * <p>The entry points are guarded by a single monitor so that store registration,
 * checkpoint triggering, restore and the accessors stay mutually consistent even when
 * callers do not share an external lock (B-22): iterating {@code storesById} while a
 * store registers used to throw ConcurrentModificationException, and concurrent
 * registration could silently drop stores.</p>
 *
 * <p>Snapshots additionally run under {@link #checkpointLock()}, the same monitor every
 * registered store synchronizes its mutations on (P1): a snapshot taken while the pipeline
 * thread was mid-write used to race the store's plain {@code HashMap} (CME / torn copies).
 * The coordinator holds the lock across all stores' snapshots, so one checkpoint is a
 * single consistent cut. This lock is deliberately disjoint from the emission lock handed
 * out by {@code SourceContext.getCheckpointLock()}: a producer may block on a full hand-off
 * queue while holding that lock, and a consumer blocked on the checkpoint lock could then
 * never drain it (deadlock). The checkpoint lock is only ever held for bounded, non-blocking
 * in-memory work.</p>
 *
 * <p>Besides keyed state stores, arbitrary named snapshot sources can join the checkpoint
 * cut via {@link #registerSnapshotSource(String, Supplier, Consumer)} (P2): their
 * snapshot/restore callbacks run under the same lock, so one checkpoint stays a single
 * consistent cut across stores and sources. Watermark states register automatically this
 * way when a pipeline segment carries {@code assignTimestampsAndWatermarks}.</p>
 */
public final class InMemoryCheckpointCoordinator implements CheckpointCoordinator {

    private final AtomicLong nextCheckpointId = new AtomicLong(1);
    private final AtomicLong nextStoreId = new AtomicLong(1);
    private final AtomicLong nextSourceId = new AtomicLong(1);

    private final Object checkpointLock = new Object();
    private final Map<String, InMemoryKeyedStateStore<?>> storesById = new LinkedHashMap<>();
    private final Map<String, SnapshotSourceHandle> sourcesById = new LinkedHashMap<>();
    private final Map<Long, Checkpoint> checkpointsById = new HashMap<>();
    private final Map<String, Object> latestRestoredStateByStoreId = new HashMap<>();
    private Checkpoint latestCheckpoint;

    /**
     * The monitor every registered store synchronizes its mutations on and every snapshot runs
     * under: holding it excludes state writes and checkpoint snapshots from each other.
     */
    public Object checkpointLock() {
        return checkpointLock;
    }

    public synchronized String registerStore(InMemoryKeyedStateStore<?> store) {
        if (store == null) {
            throw new NullPointerException("store");
        }
        String storeId = "store-" + nextStoreId.getAndIncrement();
        store.bindCheckpointLock(checkpointLock);
        storesById.put(storeId, store);

        Object restored = latestRestoredStateByStoreId.get(storeId);
        if (restored != null) {
            store.restoreFromSnapshot(restored);
        }
        return storeId;
    }

    /**
     * Registers a named snapshot source — the generalized registration face (barrier design
     * P2): anything with a {@code (name, snapshot, restore)} triple joins the checkpoint cut,
     * not just keyed state stores. Both callbacks run under {@link #checkpointLock()}, so a
     * source whose mutations synchronize on that lock is torn-free; stateless or
     * volatile-backed sources (e.g. {@link WatermarkState}) need no extra locking. Duplicate
     * names throw — use a unique name per pipeline segment.
     */
    public synchronized String registerSnapshotSource(String name,
                                                      Supplier<Object> snapshot,
                                                      Consumer<Object> restore) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Snapshot source name must not be blank");
        }
        if (snapshot == null || restore == null) {
            throw new NullPointerException("snapshot/restore");
        }
        if (sourcesById.containsKey(name) || storesById.containsKey(name)) {
            throw new IllegalStateException("Snapshot source already registered: " + name);
        }
        sourcesById.put(name, new SnapshotSourceHandle(snapshot, restore));

        Object restored = latestRestoredStateByStoreId.get(name);
        if (restored != null) {
            restore.accept(restored);
        }
        return name;
    }

    /** Registers a watermark state under a collision-free generated {@code watermark-N} name. */
    synchronized String registerWatermarkState(WatermarkState state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        String name = "watermark-" + nextSourceId.getAndIncrement();
        while (sourcesById.containsKey(name) || storesById.containsKey(name)) {
            name = "watermark-" + nextSourceId.getAndIncrement();
        }
        return registerSnapshotSource(name, state::snapshot, state::restoreSnapshot);
    }

    @Override
    public synchronized long triggerCheckpoint() {
        long checkpointId = nextCheckpointId.getAndIncrement();
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(checkpointId, System.currentTimeMillis());

        synchronized (checkpointLock) {
            for (Map.Entry<String, InMemoryKeyedStateStore<?>> entry : storesById.entrySet()) {
                String storeId = entry.getKey();
                InMemoryKeyedStateStore<?> store = entry.getValue();
                checkpoint.getStateSnapshot().putState(storeId, store.snapshot());
            }
            for (Map.Entry<String, SnapshotSourceHandle> entry : sourcesById.entrySet()) {
                checkpoint.getStateSnapshot().putState(entry.getKey(), entry.getValue().snapshot().get());
            }
        }

        checkpoint.markCompleted();
        checkpointsById.put(checkpointId, checkpoint);
        latestCheckpoint = checkpoint;
        return checkpointId;
    }

    @Override
    public void acknowledgeCheckpoint(long checkpointId, String taskId) {
        // Single-threaded in-memory runtime: snapshot is completed synchronously in triggerCheckpoint().
    }

    @Override
    public void completeCheckpoint(long checkpointId) {
        // Single-threaded in-memory runtime: snapshot is completed synchronously in triggerCheckpoint().
    }

    @Override
    public synchronized void restoreFromCheckpoint(long checkpointId) {
        Checkpoint checkpoint = getCheckpoint(checkpointId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointId);
        }

        Map<String, Object> restored = new HashMap<>();
        for (String key : checkpoint.getStateSnapshot().getKeys()) {
            restored.put(key, checkpoint.getStateSnapshot().getState(key));
        }
        latestRestoredStateByStoreId.clear();
        latestRestoredStateByStoreId.putAll(restored);

        synchronized (checkpointLock) {
            for (Map.Entry<String, InMemoryKeyedStateStore<?>> entry : storesById.entrySet()) {
                Object snapshot = restored.get(entry.getKey());
                if (snapshot != null) {
                    entry.getValue().restoreFromSnapshot(snapshot);
                }
            }
            for (Map.Entry<String, SnapshotSourceHandle> entry : sourcesById.entrySet()) {
                Object snapshot = restored.get(entry.getKey());
                if (snapshot != null) {
                    entry.getValue().restore().accept(snapshot);
                }
            }
        }
    }

    @Override
    public synchronized Checkpoint getLatestCheckpoint() {
        return latestCheckpoint;
    }

    @Override
    public synchronized Checkpoint getCheckpoint(long checkpointId) {
        return checkpointsById.get(checkpointId);
    }

    /**
     * Returns an immutable copy of the currently registered stores; iteration is stable
     * even while other threads register stores (B-22: a live view raced with mutation).
     */
    synchronized Map<String, InMemoryKeyedStateStore<?>> getRegisteredStores() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(storesById));
    }

    /** Names of the registered generalized snapshot sources (test visibility). */
    synchronized java.util.Set<String> getRegisteredSourceNames() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(sourcesById.keySet()));
    }

    /** A registered {@code (snapshot, restore)} pair participating in the checkpoint cut. */
    private record SnapshotSourceHandle(Supplier<Object> snapshot, Consumer<Object> restore) {
    }
}

