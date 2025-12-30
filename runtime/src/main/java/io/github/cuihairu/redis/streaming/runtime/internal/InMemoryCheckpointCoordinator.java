package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.checkpoint.CheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory checkpoint coordinator for the in-memory runtime.
 *
 * <p>This implementation is single-process and snapshots registered keyed-state stores
 * synchronously when {@link #triggerCheckpoint()} is called.</p>
 */
public final class InMemoryCheckpointCoordinator implements CheckpointCoordinator {

    private final AtomicLong nextCheckpointId = new AtomicLong(1);
    private final AtomicLong nextStoreId = new AtomicLong(1);

    private final Map<String, InMemoryKeyedStateStore<?>> storesById = new LinkedHashMap<>();
    private final Map<Long, Checkpoint> checkpointsById = new HashMap<>();
    private final Map<String, Object> latestRestoredStateByStoreId = new HashMap<>();
    private volatile Checkpoint latestCheckpoint;

    public String registerStore(InMemoryKeyedStateStore<?> store) {
        if (store == null) {
            throw new NullPointerException("store");
        }
        String storeId = "store-" + nextStoreId.getAndIncrement();
        storesById.put(storeId, store);

        Object restored = latestRestoredStateByStoreId.get(storeId);
        if (restored != null) {
            store.restoreFromSnapshot(restored);
        }
        return storeId;
    }

    @Override
    public long triggerCheckpoint() {
        long checkpointId = nextCheckpointId.getAndIncrement();
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(checkpointId, System.currentTimeMillis());

        for (Map.Entry<String, InMemoryKeyedStateStore<?>> entry : storesById.entrySet()) {
            String storeId = entry.getKey();
            InMemoryKeyedStateStore<?> store = entry.getValue();
            checkpoint.getStateSnapshot().putState(storeId, store.snapshot());
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
    public void restoreFromCheckpoint(long checkpointId) {
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

        for (Map.Entry<String, InMemoryKeyedStateStore<?>> entry : storesById.entrySet()) {
            Object snapshot = restored.get(entry.getKey());
            if (snapshot != null) {
                entry.getValue().restoreFromSnapshot(snapshot);
            }
        }
    }

    @Override
    public Checkpoint getLatestCheckpoint() {
        return latestCheckpoint;
    }

    @Override
    public Checkpoint getCheckpoint(long checkpointId) {
        return checkpointsById.get(checkpointId);
    }

    Map<String, InMemoryKeyedStateStore<?>> getRegisteredStores() {
        return Collections.unmodifiableMap(storesById);
    }
}

