package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit lane for the {@code restoreFromCheckpoint} sink-delivery contract (B-13): a
 * completed checkpoint's snapshot entries are handed to the caller's {@code BiConsumer}
 * one by one, the returned count matches, and the two-argument convenience overload runs
 * the same delivery through its logging sink. The real-Redis counterpart lives in
 * {@code CheckpointSnapshotRoundTripIntegrationTest} (integration lane).
 */
class RedisCheckpointCoordinatorRestoreSinkTest {

    @Test
    void completedCheckpointHandsEveryEntryToTheSinkAndReturnsTheCount() {
        InMemoryStorage storage = new InMemoryStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        DefaultCheckpoint checkpoint = new DefaultCheckpoint(7, System.currentTimeMillis());
        checkpoint.getStateSnapshot().putState("counter", 42);
        checkpoint.getStateSnapshot().putState("greeting", "hello");
        checkpoint.markCompleted();
        storage.storeCheckpoint(checkpoint);

        Map<String, Object> delivered = new HashMap<>();
        int count = coordinator.restoreFromCheckpoint(7, delivered::put);

        assertEquals(2, count, "the returned count must equal the number of snapshot entries");
        assertEquals(42, delivered.get("counter"));
        assertEquals("hello", delivered.get("greeting"));
        assertInstanceOf(Integer.class, delivered.get("counter"),
                "values must reach the sink with their runtime type intact");

        coordinator.close();
    }

    @Test
    void twoArgRestoreDelegatesToTheLoggingSink() {
        InMemoryStorage storage = new InMemoryStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        DefaultCheckpoint checkpoint = new DefaultCheckpoint(8, System.currentTimeMillis());
        checkpoint.getStateSnapshot().putState("offsets", Map.of("p0", 11L, "p1", 22L));
        checkpoint.markCompleted();
        storage.storeCheckpoint(checkpoint);

        // the two-argument overload has no return value and a logging sink — with state
        // present it must deliver every entry there instead of throwing
        assertDoesNotThrow(() -> coordinator.restoreFromCheckpoint(8));

        coordinator.close();
    }

    @Test
    void throwingSinkSurfacesAsMinusOne() {
        InMemoryStorage storage = new InMemoryStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        DefaultCheckpoint checkpoint = new DefaultCheckpoint(9, System.currentTimeMillis());
        checkpoint.getStateSnapshot().putState("k", "v");
        checkpoint.markCompleted();
        storage.storeCheckpoint(checkpoint);

        List<String> seen = new ArrayList<>();
        int count = coordinator.restoreFromCheckpoint(9, (key, value) -> {
            seen.add(key);
            throw new IllegalStateException("sink exploded");
        });

        assertEquals(-1, count, "a failing sink must be reported as -1, not a partial count");
        assertEquals(1, seen.size(), "exactly the first entry reached the sink before the failure");

        coordinator.close();
    }

    /** Minimal {@link CheckpointStorage} fake — mirrors the helper in RedisCheckpointCoordinatorTest. */
    private static final class InMemoryStorage implements CheckpointStorage {

        private final Map<Long, Checkpoint> checkpointsById = new ConcurrentHashMap<>();

        @Override
        public void storeCheckpoint(Checkpoint checkpoint) {
            checkpointsById.put(checkpoint.getCheckpointId(), checkpoint);
        }

        @Override
        public Checkpoint loadCheckpoint(long checkpointId) {
            return checkpointsById.get(checkpointId);
        }

        @Override
        public Checkpoint getLatestCheckpoint() {
            return checkpointsById.values().stream()
                    .filter(Checkpoint::isCompleted)
                    .max(Comparator.comparingLong(Checkpoint::getTimestamp)
                            .thenComparingLong(Checkpoint::getCheckpointId))
                    .orElse(null);
        }

        @Override
        public List<Checkpoint> listCheckpoints(int limit) {
            return checkpointsById.values().stream()
                    .sorted(Comparator.comparingLong(Checkpoint::getTimestamp).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean deleteCheckpoint(long checkpointId) {
            return checkpointsById.remove(checkpointId) != null;
        }

        @Override
        public int cleanupOldCheckpoints(int keepCount) {
            List<Checkpoint> byAge = listCheckpoints(Integer.MAX_VALUE);
            int deleted = 0;
            for (int i = keepCount; i < byAge.size(); i++) {
                if (deleteCheckpoint(byAge.get(i).getCheckpointId())) {
                    deleted++;
                }
            }
            return deleted;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
