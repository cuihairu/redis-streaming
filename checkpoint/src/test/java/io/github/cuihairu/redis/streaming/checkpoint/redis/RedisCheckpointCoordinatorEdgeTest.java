package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class RedisCheckpointCoordinatorEdgeTest {

    @Test
    void duplicateAckDoesNotCountTwice() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();

        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertFalse(checkpoint.isCompleted());
        assertEquals(1, coordinator.getPendingCheckpointCount());

        coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
        assertTrue(storage.loadCheckpoint(checkpointId).isCompleted());
        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
    }

    @Test
    void acknowledgeUnknownCheckpointIsIgnored() {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        coordinator.acknowledgeCheckpoint(123, "task-1");
        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
    }

    @Test
    void cleanupExpiredPendingCheckpointsRemovesTimedOutOnes() {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 20);

        long checkpointId = coordinator.triggerCheckpoint();
        assertEquals(1, coordinator.getPendingCheckpointCount());

        sleepQuietly(80);
        int removed = coordinator.cleanupExpiredPendingCheckpoints();

        assertEquals(1, removed);
        assertEquals(0, coordinator.getPendingCheckpointCount());

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertFalse(checkpoint.isCompleted());

        coordinator.close();
    }

    @Test
    void triggerCheckpointReturnsMinusOneWhenStorageFails() {
        CheckpointStorage storage = new CheckpointStorage() {
            @Override public void storeCheckpoint(Checkpoint checkpoint) {
                throw new RuntimeException("boom");
            }

            @Override public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override public List<Checkpoint> listCheckpoints(int limit) {
                return List.of();
            }

            @Override public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override public void close() {
            }
        };

        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);
        assertEquals(-1, coordinator.triggerCheckpoint());
        assertEquals(0, coordinator.getPendingCheckpointCount());
        coordinator.close();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class InMemoryCheckpointStorage implements CheckpointStorage {

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
            return checkpointsById.values()
                    .stream()
                    .max(Comparator.comparingLong(Checkpoint::getTimestamp).thenComparingLong(Checkpoint::getCheckpointId))
                    .orElse(null);
        }

        @Override
        public List<Checkpoint> listCheckpoints(int limit) {
            List<Checkpoint> checkpoints = new ArrayList<>(checkpointsById.values());
            checkpoints.sort((c1, c2) -> Long.compare(c2.getTimestamp(), c1.getTimestamp()));
            return checkpoints.stream().limit(limit).toList();
        }

        @Override
        public boolean deleteCheckpoint(long checkpointId) {
            return checkpointsById.remove(checkpointId) != null;
        }

        @Override
        public int cleanupOldCheckpoints(int keepCount) {
            List<Checkpoint> checkpoints = listCheckpoints(Integer.MAX_VALUE);
            if (checkpoints.size() <= keepCount) {
                return 0;
            }

            int deletedCount = 0;
            for (int index = keepCount; index < checkpoints.size(); index++) {
                if (deleteCheckpoint(checkpoints.get(index).getCheckpointId())) {
                    deletedCount++;
                }
            }
            return deletedCount;
        }

        @Override
        public void close() {
        }
    }
}
