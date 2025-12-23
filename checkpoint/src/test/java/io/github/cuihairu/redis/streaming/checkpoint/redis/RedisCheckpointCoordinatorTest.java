package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class RedisCheckpointCoordinatorTest {

    @Test
    void completesCheckpointBeforeTimeout() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertTrue(checkpoint.isCompleted());
        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
    }

    @Test
    void doesNotCompleteCheckpointAfterTimeout() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 30);

        long checkpointId = coordinator.triggerCheckpoint();
        Thread.sleep(80);
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertFalse(checkpoint.isCompleted());
        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
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

