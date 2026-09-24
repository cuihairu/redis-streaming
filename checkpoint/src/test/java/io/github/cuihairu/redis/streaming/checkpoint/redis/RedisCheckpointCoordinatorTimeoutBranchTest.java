package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers the in-method expiry branches of {@code acknowledgeCheckpoint} and
 * {@code completeCheckpoint}. {@code cleanupExpiredPendingCheckpoints()} runs first on every call,
 * so an already-expired pending checkpoint would normally be reaped there; a map whose
 * {@code entrySet()} is empty keeps the seeded pending entry invisible to the cleanup pass while
 * {@code get}/{@code remove} still see it, which deterministically lands the later
 * {@code isExpired(...)} checks in their true branches (a plain timing race otherwise).
 */
class RedisCheckpointCoordinatorTimeoutBranchTest {

    private static final long TIMEOUT_MS = 60_000L;

    @Test
    void acknowledgeExpiredPendingRemovesItAndSkipsAckRegistration() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, TIMEOUT_MS);

        long checkpointId = 7L;
        Object pending = newPendingCheckpoint(System.currentTimeMillis() - 10 * TIMEOUT_MS);
        installGapMap(coordinator, checkpointId, pending, true);

        assertDoesNotThrow(() -> coordinator.acknowledgeCheckpoint(checkpointId, "task-1"));

        assertTrue(acknowledgementsOf(pending).isEmpty(),
                "expired pending must return before acknowledging the task");
        assertEquals(0, gapMapOf(coordinator).size(), "expired pending must be removed on the success path");
    }

    @Test
    void acknowledgeExpiredPendingKeepsEntryWhenRemoveLosesRace() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, TIMEOUT_MS);

        long checkpointId = 8L;
        Object pending = newPendingCheckpoint(System.currentTimeMillis() - 10 * TIMEOUT_MS);
        installGapMap(coordinator, checkpointId, pending, false);

        assertDoesNotThrow(() -> coordinator.acknowledgeCheckpoint(checkpointId, "task-1"));

        assertTrue(acknowledgementsOf(pending).isEmpty());
        assertEquals(1, gapMapOf(coordinator).size(), "failed remove keeps the entry, the warning is skipped");
    }

    @Test
    void completeCheckpointSkipsExpiredPending() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, TIMEOUT_MS);

        long checkpointId = 9L;
        Object pending = newPendingCheckpoint(System.currentTimeMillis() - 10 * TIMEOUT_MS);
        installGapMap(coordinator, checkpointId, pending, true);

        assertDoesNotThrow(() -> coordinator.completeCheckpoint(checkpointId));

        assertEquals(0, gapMapOf(coordinator).size(), "the expired pending is still removed by remove(id)");
        verify(storage, never()).loadCheckpoint(anyLong());
        assertNotNull(pending);
    }

    private static Object newPendingCheckpoint(long createdAtMillis) throws Exception {
        Class<?> pendingClass = Class.forName(
                "io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator$PendingCheckpoint");
        Constructor<?> ctor = pendingClass.getDeclaredConstructor(long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(createdAtMillis);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> acknowledgementsOf(Object pending) throws Exception {
        Field f = pending.getClass().getDeclaredField("acknowledgements");
        f.setAccessible(true);
        return (Map<Long, Object>) f.get(pending);
    }

    private static void installGapMap(RedisCheckpointCoordinator coordinator,
                                      long checkpointId,
                                      Object pending,
                                      boolean removeSucceeds) throws Exception {
        Map<Long, Object> gap = new GapMap<>(removeSucceeds);
        gap.put(checkpointId, pending);
        pendingMapField().set(coordinator, gap);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> gapMapOf(RedisCheckpointCoordinator coordinator) throws Exception {
        return (Map<Long, Object>) pendingMapField().get(coordinator);
    }

    private static Field pendingMapField() throws Exception {
        Field f = RedisCheckpointCoordinator.class.getDeclaredField("pendingCheckpoints");
        f.setAccessible(true);
        return f;
    }

    /**
     * Map view that hides its entries from {@code entrySet()} (so the cleanup sweep is a no-op)
     * while {@code get}/{@code remove} keep working on the backing store.
     */
    private static final class GapMap<K, V> extends HashMap<K, V> {
        private final boolean removeSucceeds;

        GapMap(boolean removeSucceeds) {
            this.removeSucceeds = removeSucceeds;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return Set.of();
        }

        @Override
        public boolean remove(Object key, Object value) {
            return removeSucceeds && super.remove(key, value);
        }
    }
}
