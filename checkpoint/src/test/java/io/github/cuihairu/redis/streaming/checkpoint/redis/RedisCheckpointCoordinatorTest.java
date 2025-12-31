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

    @Test
    void completesWithMultipleTasks() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();
        assertEquals(1, coordinator.getPendingCheckpointCount());

        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
        assertEquals(1, coordinator.getPendingCheckpointCount());

        coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
        assertEquals(1, coordinator.getPendingCheckpointCount());

        coordinator.acknowledgeCheckpoint(checkpointId, "task-3");
        assertEquals(0, coordinator.getPendingCheckpointCount());

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertTrue(checkpoint.isCompleted());

        coordinator.close();
    }

    @Test
    void triggerMultipleCheckpoints() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long id1 = coordinator.triggerCheckpoint();
        long id2 = coordinator.triggerCheckpoint();
        long id3 = coordinator.triggerCheckpoint();

        assertEquals(0, id1);
        assertEquals(1, id2);
        assertEquals(2, id3);

        assertEquals(3, coordinator.getPendingCheckpointCount());

        coordinator.acknowledgeCheckpoint(id1, "task-1");
        coordinator.acknowledgeCheckpoint(id2, "task-1");
        coordinator.acknowledgeCheckpoint(id3, "task-1");

        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
    }

    @Test
    void directCompleteCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();

        // Directly complete without all acknowledgements
        coordinator.completeCheckpoint(checkpointId);

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertTrue(checkpoint.isCompleted());
        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
    }

    @Test
    void getLatestCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        assertNull(coordinator.getLatestCheckpoint());

        long id1 = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id1, "task-1");

        long id2 = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id2, "task-1");

        Checkpoint latest = coordinator.getLatestCheckpoint();
        assertNotNull(latest);
        assertEquals(id2, latest.getCheckpointId());

        coordinator.close();
    }

    @Test
    void getCheckpointById() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertEquals(checkpointId, checkpoint.getCheckpointId());

        coordinator.close();
    }

    @Test
    void getNonExistentCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        Checkpoint checkpoint = coordinator.getCheckpoint(999);
        assertNull(checkpoint);

        coordinator.close();
    }

    @Test
    void cleanupOldCheckpoints() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        // Create 5 checkpoints
        for (int i = 0; i < 5; i++) {
            long id = coordinator.triggerCheckpoint();
            coordinator.acknowledgeCheckpoint(id, "task-1");
        }

        // Keep only 3 most recent
        int deleted = coordinator.cleanupOldCheckpoints(3);
        assertEquals(2, deleted);

        // Verify only 3 remain
        List<Checkpoint> remaining = storage.listCheckpoints(100);
        assertEquals(3, remaining.size());

        coordinator.close();
    }

    @Test
    void cleanupExpiredPendingCheckpoints() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 50);

        long id1 = coordinator.triggerCheckpoint();
        long id2 = coordinator.triggerCheckpoint();

        assertEquals(2, coordinator.getPendingCheckpointCount());

        Thread.sleep(100);

        // Trigger cleanup via getPendingCheckpointCount
        int count = coordinator.getPendingCheckpointCount();
        assertEquals(0, count);

        // Checkpoints should still exist but not be completed
        Checkpoint cp1 = storage.loadCheckpoint(id1);
        Checkpoint cp2 = storage.loadCheckpoint(id2);

        assertNotNull(cp1);
        assertNotNull(cp2);
        assertFalse(cp1.isCompleted());
        assertFalse(cp2.isCompleted());

        coordinator.close();
    }

    @Test
    void duplicateAcknowledgementIgnored() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();

        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1"); // Duplicate

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertTrue(checkpoint.isCompleted());

        coordinator.close();
    }

    @Test
    void acknowledgeAfterComplete() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        // Already completed
        assertEquals(0, coordinator.getPendingCheckpointCount());

        // Ack again - should be harmless
        coordinator.acknowledgeCheckpoint(checkpointId, "task-2");

        assertEquals(0, coordinator.getPendingCheckpointCount());

        coordinator.close();
    }

    @Test
    void defaultTimeoutConstructor() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1);

        // Should use default 60s timeout
        long checkpointId = coordinator.triggerCheckpoint();

        Thread.sleep(100);

        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertTrue(checkpoint.isCompleted());

        coordinator.close();
    }

    @Test
    void disableTimeoutWithZero() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 0);

        long checkpointId = coordinator.triggerCheckpoint();

        Thread.sleep(100);

        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
        assertTrue(checkpoint.isCompleted());

        assertEquals(0, coordinator.cleanupExpiredPendingCheckpoints());

        coordinator.close();
    }

    @Test
    void restoreFromCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        // Should not throw
        assertDoesNotThrow(() -> coordinator.restoreFromCheckpoint(checkpointId));

        coordinator.close();
    }

    @Test
    void restoreFromNonExistentCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        // Should not throw
        assertDoesNotThrow(() -> coordinator.restoreFromCheckpoint(999));

        coordinator.close();
    }

    @Test
    void restoreFromIncompleteCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3, 10_000);

        long checkpointId = coordinator.triggerCheckpoint();
        // Only 1 of 3 acks received
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

        // Manually mark as completed for testing
        Checkpoint cp = storage.loadCheckpoint(checkpointId);
        cp.markCompleted();
        storage.storeCheckpoint(cp);

        // Should not throw
        assertDoesNotThrow(() -> coordinator.restoreFromCheckpoint(checkpointId));

        coordinator.close();
    }

    @Test
    void initializeFromExistingCheckpoints() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();

        // Create a checkpoint directly in storage
        io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint existing =
            new io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint(5, System.currentTimeMillis());
        existing.markCompleted();
        storage.storeCheckpoint(existing);

        // New coordinator should start from id 6
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long newId = coordinator.triggerCheckpoint();
        assertEquals(6, newId);

        coordinator.close();
    }

    @Test
    void completeUnknownCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        // Should not throw
        assertDoesNotThrow(() -> coordinator.completeCheckpoint(999));

        coordinator.close();
    }

    @Test
    void acknowledgeUnknownCheckpoint() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        // Should not throw
        assertDoesNotThrow(() -> coordinator.acknowledgeCheckpoint(999, "task-1"));

        coordinator.close();
    }

    @Test
    void cleanupKeepsMostRecent() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        // Create multiple checkpoints
        for (int i = 0; i < 10; i++) {
            long id = coordinator.triggerCheckpoint();
            coordinator.acknowledgeCheckpoint(id, "task-1");
            Thread.sleep(5); // Ensure different timestamps
        }

        // Keep 5 most recent
        int deleted = coordinator.cleanupOldCheckpoints(5);
        assertEquals(5, deleted);

        // Verify latest is still there
        Checkpoint latest = coordinator.getLatestCheckpoint();
        assertNotNull(latest);

        // Total should be 5
        List<Checkpoint> remaining = storage.listCheckpoints(100);
        assertEquals(5, remaining.size());

        coordinator.close();
    }

    @Test
    void cleanupWhenNothingToDelete() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        long id = coordinator.triggerCheckpoint();
        coordinator.acknowledgeCheckpoint(id, "task-1");

        // Keep 10, only have 1
        int deleted = coordinator.cleanupOldCheckpoints(10);
        assertEquals(0, deleted);

        coordinator.close();
    }

    @Test
    void closeClearsPendingCheckpoints() throws Exception {
        InMemoryCheckpointStorage storage = new InMemoryCheckpointStorage();
        RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 1, 10_000);

        coordinator.triggerCheckpoint();
        coordinator.triggerCheckpoint();

        assertEquals(2, coordinator.getPendingCheckpointCount());

        coordinator.close();

        assertEquals(0, coordinator.getPendingCheckpointCount());
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

