package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Covers the {@code cleanupExpiredPendingCheckpoints} branch taken when the
 * compare-and-remove loses against a concurrent completion: the entry is kept
 * and the timeout warning is skipped.
 */
class RedisCheckpointCoordinatorCleanupRaceCoverageTest {

    @Test
    void cleanupKeepsEntryWhenConcurrentRemoveWinsTheRace() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 60_000);

        Object pending = newPendingCheckpoint(System.currentTimeMillis() - 120_000);
        Map<Long, Object> map = new HashMap<>() {
            @Override
            public boolean remove(Object key, Object value) {
                return false;
            }
        };
        map.put(11L, pending);
        pendingMapField().set(coordinator, map);

        assertEquals(0, coordinator.cleanupExpiredPendingCheckpoints(),
                "a lost compare-and-remove must not be counted as a timeout removal");
        assertEquals(1, map.size(), "the entry must be kept when the compare-and-remove loses");
    }

    @Test
    void cleanupCountsRemovalWhenCompareAndRemoveWins() throws Exception {
        CheckpointStorage storage = mock(CheckpointStorage.class);
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 60_000);

        Object pending = newPendingCheckpoint(System.currentTimeMillis() - 120_000);
        Map<Long, Object> map = new HashMap<>();
        map.put(12L, pending);
        pendingMapField().set(coordinator, map);

        assertEquals(1, coordinator.cleanupExpiredPendingCheckpoints());
        assertEquals(0, map.size());
    }

    private static Object newPendingCheckpoint(long createdAtMillis) throws Exception {
        Class<?> pendingClass = Class.forName(
                "io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator$PendingCheckpoint");
        Constructor<?> ctor = pendingClass.getDeclaredConstructor(long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(createdAtMillis);
    }

    private static Field pendingMapField() throws Exception {
        Field f = RedisCheckpointCoordinator.class.getDeclaredField("pendingCheckpoints");
        f.setAccessible(true);
        return f;
    }
}
