package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for checkpoint module using InMemoryCheckpointStorage (no Redis required).
 * Follows the same pattern as RedisCheckpointCoordinatorTest in the redis sub-package.
 */
class CheckpointUnitTest {

    @Test
    void creationSetsIdAndTimestampAndNotCompleted() {
        long id = 42L;
        long ts = 1234567890L;
        DefaultCheckpoint cp = new DefaultCheckpoint(id, ts);

        assertEquals(id, cp.getCheckpointId(), "checkpoint id must match constructor");
        assertEquals(ts, cp.getTimestamp(), "timestamp must match constructor");
        assertFalse(cp.isCompleted(), "new checkpoint must not be completed");
    }

    @Test
    void markCompletedTogglesIsCompleted() {
        DefaultCheckpoint cp = new DefaultCheckpoint(1L, 1L);
        assertFalse(cp.isCompleted());

        cp.markCompleted();
        assertTrue(cp.isCompleted(), "after markCompleted, isCompleted must return true");

        cp.markCompleted();
        assertTrue(cp.isCompleted(), "markCompleted is idempotent");
    }

    @Test
    void snapshotVersionIsCurrentByDefault() {
        DefaultCheckpoint cp = new DefaultCheckpoint(1L, 1L);
        assertEquals(DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION, cp.getSnapshotVersion(),
                "fresh checkpoint must carry current snapshot version");
    }

    @Test
    void legacyCheckpointVersionIsZero() {
        // An implementation written before versioning exists has no marker at all
        Checkpoint legacy = new Checkpoint() {
            @Override public long getCheckpointId() {
                return 1;
            }

            @Override public long getTimestamp() {
                return 1;
            }

            @Override public Checkpoint.StateSnapshot getStateSnapshot() {
                return new Checkpoint.StateSnapshot() {
                    @Override
                    public <T> T getState(String key) {
                        return null;
                    }

                    @Override
                    public <T> T getState(String key, Class<T> type) {
                        return null;
                    }

                    @Override
                    public <T> void putState(String key, T value) {
                        // legacy implementation does nothing
                    }

                    @Override
                    public Iterable<String> getKeys() {
                        return java.util.List.of();
                    }
                };
            }

            @Override public boolean isCompleted() {
                return true;
            }

            @Override public void markCompleted() {
            }
        };
        assertEquals(0, legacy.getSnapshotVersion(),
                "legacy checkpoint (no version marker) must read as version 0");
    }

    @Test
    void storeAndLoadCheckpoint() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();
        DefaultCheckpoint cp = new DefaultCheckpoint(1L, 1000L);
        storage.storeCheckpoint(cp);

        Checkpoint loaded = storage.loadCheckpoint(1L);
        assertNotNull(loaded, "loaded checkpoint must not be null");
        assertEquals(1L, loaded.getCheckpointId(), "id must match");
        assertEquals(1000L, loaded.getTimestamp(), "timestamp must match");
    }

    @Test
    void loadNonExistentCheckpointReturnsNull() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();
        Checkpoint cp = storage.loadCheckpoint(99L);
        assertNull(cp, "loading non-existent id must return null");
    }

    @Test
    void listCheckpointsOrderedByTimestampDescending() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();

        DefaultCheckpoint c1 = new DefaultCheckpoint(1L, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2L, 2000L);
        DefaultCheckpoint c3 = new DefaultCheckpoint(3L, 3000L);

        storage.storeCheckpoint(c1);
        storage.storeCheckpoint(c2);
        storage.storeCheckpoint(c3);

        List<Checkpoint> listed = storage.listCheckpoints(10);
        assertEquals(3, listed.size());
        assertEquals(3L, listed.get(0).getCheckpointId(), "newest first");
        assertEquals(2L, listed.get(1).getCheckpointId());
        assertEquals(1L, listed.get(2).getCheckpointId(), "oldest last");
    }

    @Test
    void getLatestCheckpointReturnsNewestCompleted() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();

        DefaultCheckpoint c1 = new DefaultCheckpoint(1L, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2L, 2000L);
        c2.markCompleted();

        storage.storeCheckpoint(c1);
        storage.storeCheckpoint(c2);

        Checkpoint latest = storage.getLatestCheckpoint();
        assertNotNull(latest, "latest must exist when at least one is completed");
        assertEquals(2L, latest.getCheckpointId(), "latest must be the newest completed");
    }

    @Test
    void getLatestCheckpointReturnsNullWhenAllIncomplete() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();

        DefaultCheckpoint c1 = new DefaultCheckpoint(1L, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2L, 2000L);

        storage.storeCheckpoint(c1);
        storage.storeCheckpoint(c2);

        Checkpoint latest = storage.getLatestCheckpoint();
        assertNull(latest, "latest must be null when no checkpoint is completed");
    }

    @Test
    void deleteCheckpointRemovesIt() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();
        DefaultCheckpoint cp = new DefaultCheckpoint(1L, 1000L);

        storage.storeCheckpoint(cp);
        boolean deleted = storage.deleteCheckpoint(1L);
        assertTrue(deleted, "deleting existing checkpoint must return true");

        Checkpoint afterDelete = storage.loadCheckpoint(1L);
        assertNull(afterDelete, "checkpoint must be gone after delete");
    }

    @Test
    void deleteNonExistentCheckpointReturnsFalse() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();
        boolean deleted = storage.deleteCheckpoint(99L);
        assertFalse(deleted, "deleting non-existent checkpoint must return false");
    }

    @Test
    void cleanupOldCheckpointsKeepsMostRecent() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();

        DefaultCheckpoint c1 = new DefaultCheckpoint(1L, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2L, 2000L);
        DefaultCheckpoint c3 = new DefaultCheckpoint(3L, 3000L);

        storage.storeCheckpoint(c1);
        storage.storeCheckpoint(c2);
        storage.storeCheckpoint(c3);

        int deleted = storage.cleanupOldCheckpoints(1);
        assertEquals(2, deleted, "must delete 2 oldest, keep 1 newest");

        List<Checkpoint> remaining = storage.listCheckpoints(10);
        assertEquals(1, remaining.size(), "must have exactly 1 remaining");
        assertEquals(3L, remaining.get(0).getCheckpointId(), "must keep the newest");
    }

    @Test
    void cleanupOldCheckpointsWithMoreThanAvailable() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();

        DefaultCheckpoint c1 = new DefaultCheckpoint(1L, 1000L);
        storage.storeCheckpoint(c1);

        int deleted = storage.cleanupOldCheckpoints(10);
        assertEquals(0, deleted, "must delete 0 when having fewer than keepCount");
    }

    @Test
    void cleanupOldCheckpointsWithZeroKeepCountDeletesAll() throws Exception {
        CheckpointStorage storage = new InMemoryCheckpointStorage();

        DefaultCheckpoint c1 = new DefaultCheckpoint(1L, 1000L);
        DefaultCheckpoint c2 = new DefaultCheckpoint(2L, 2000L);

        storage.storeCheckpoint(c1);
        storage.storeCheckpoint(c2);

        int deleted = storage.cleanupOldCheckpoints(0);
        assertEquals(2, deleted, "must delete all when keepCount is 0");

        List<Checkpoint> remaining = storage.listCheckpoints(10);
        assertTrue(remaining.isEmpty(), "must have no remaining checkpoints");
    }

    @Test
    void closeIsNoop() {
        CheckpointStorage storage = new InMemoryCheckpointStorage();
        storage.close(); // must not throw
    }

    /** Package-private implementation used by tests in the same package. */
    static final class InMemoryCheckpointStorage implements CheckpointStorage {

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
                    .filter(Checkpoint::isCompleted)
                    .max(java.util.Comparator.comparingLong(Checkpoint::getTimestamp)
                            .thenComparingLong(Checkpoint::getCheckpointId))
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
            List<Checkpoint> byAge = listCheckpoints(Integer.MAX_VALUE);
            if (checkpointsById.size() <= keepCount) {
                return 0;
            }

            int deletedCount = 0;
            for (int index = keepCount; index < byAge.size(); index++) {
                if (deleteCheckpoint(byAge.get(index).getCheckpointId())) {
                    deletedCount++;
                }
            }
            return deletedCount;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}