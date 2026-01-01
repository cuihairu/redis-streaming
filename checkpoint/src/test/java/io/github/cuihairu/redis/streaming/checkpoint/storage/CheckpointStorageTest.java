package io.github.cuihairu.redis.streaming.checkpoint.storage;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CheckpointStorage interface
 */
class CheckpointStorageTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then - verify interface structure
        assertTrue(CheckpointStorage.class.isInterface());
        // Interface has 7 methods (excluding Object methods)
        assertTrue(CheckpointStorage.class.getMethods().length >= 7);
    }

    @Test
    void testStoreCheckpointMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("storeCheckpoint", Checkpoint.class));
    }

    @Test
    void testLoadCheckpointMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("loadCheckpoint", long.class));
    }

    @Test
    void testGetLatestCheckpointMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("getLatestCheckpoint"));
    }

    @Test
    void testListCheckpointsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("listCheckpoints", int.class));
    }

    @Test
    void testDeleteCheckpointMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("deleteCheckpoint", long.class));
    }

    @Test
    void testCleanupOldCheckpointsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("cleanupOldCheckpoints", int.class));
    }

    @Test
    void testCloseMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(CheckpointStorage.class.getMethod("close"));
    }

    @Test
    void testSimpleImplementation() throws Exception {
        // Given - create a simple in-memory implementation for testing
        CheckpointStorage storage = new CheckpointStorage() {
            private final java.util.Map<Long, Checkpoint> checkpoints = new java.util.HashMap<>();

            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
                checkpoints.put(checkpoint.getCheckpointId(), checkpoint);
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return checkpoints.get(checkpointId);
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return checkpoints.values().stream()
                    .max(java.util.Comparator.comparingLong(Checkpoint::getTimestamp))
                    .orElse(null);
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return checkpoints.values().stream()
                    .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                    .limit(limit)
                    .collect(java.util.stream.Collectors.toList());
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return checkpoints.remove(checkpointId) != null;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                List<Checkpoint> sorted = listCheckpoints(Integer.MAX_VALUE);
                int toDelete = Math.max(0, sorted.size() - keepCount);
                for (int i = sorted.size() - 1; i >= sorted.size() - toDelete; i--) {
                    checkpoints.remove(sorted.get(i).getCheckpointId());
                }
                return toDelete;
            }

            @Override
            public void close() {
                checkpoints.clear();
            }
        };

        // When - store checkpoints
        Checkpoint cp1 = new DefaultCheckpoint(1, 1000);
        Checkpoint cp2 = new DefaultCheckpoint(2, 2000);
        Checkpoint cp3 = new DefaultCheckpoint(3, 3000);

        storage.storeCheckpoint(cp1);
        storage.storeCheckpoint(cp2);
        storage.storeCheckpoint(cp3);

        // Then - verify operations
        assertNotNull(storage.loadCheckpoint(1));
        assertNotNull(storage.loadCheckpoint(2));
        assertNotNull(storage.loadCheckpoint(3));
        assertEquals(cp3, storage.getLatestCheckpoint());
        assertEquals(3, storage.listCheckpoints(10).size());
        assertTrue(storage.deleteCheckpoint(1));
        assertNull(storage.loadCheckpoint(1));
    }

    @Test
    void testStoreCheckpointThrowsException() {
        // Given - implementation that throws exception
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) throws Exception {
                throw new Exception("Storage error");
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
            }
        };

        // When & Then
        assertThrows(Exception.class, () -> storage.storeCheckpoint(new DefaultCheckpoint(1, 1000)));
    }

    @Test
    void testLoadCheckpointReturnsNullForNonExistent() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null; // Always return null
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
            }
        };

        // When
        Checkpoint result = storage.loadCheckpoint(999);

        // Then
        assertNull(result);
    }

    @Test
    void testGetLatestCheckpointReturnsNullWhenEmpty() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null; // No checkpoints
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
            }
        };

        // When
        Checkpoint result = storage.getLatestCheckpoint();

        // Then
        assertNull(result);
    }

    @Test
    void testDeleteCheckpointReturnsFalseForNonExistent() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false; // Not found
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
            }
        };

        // When
        boolean result = storage.deleteCheckpoint(999);

        // Then
        assertFalse(result);
    }

    @Test
    void testListCheckpointsWithLimit() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                // Return exactly limit checkpoints
                return java.util.stream.IntStream.range(0, limit)
                    .mapToObj(i -> new DefaultCheckpoint(i, i * 1000L))
                    .collect(java.util.stream.Collectors.toList());
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
            }
        };

        // When
        List<Checkpoint> result = storage.listCheckpoints(5);

        // Then
        assertEquals(5, result.size());
    }

    @Test
    void testCleanupOldCheckpoints() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            private int count = 10;

            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return true;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                int toDelete = Math.max(0, count - keepCount);
                count -= toDelete;
                return toDelete;
            }

            @Override
            public void close() {
            }
        };

        // When
        int deleted = storage.cleanupOldCheckpoints(5);

        // Then - should delete 5, keeping 5
        assertEquals(5, deleted);
    }

    @Test
    void testCleanupOldCheckpointsWithMoreKeepThanExist() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0; // Nothing to delete
            }

            @Override
            public void close() {
            }
        };

        // When
        int deleted = storage.cleanupOldCheckpoints(100);

        // Then
        assertEquals(0, deleted);
    }

    @Test
    void testCloseMethod() {
        // Given
        final boolean[] closed = {false};
        CheckpointStorage storage = new CheckpointStorage() {
            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return null;
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return null;
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return false;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        // When
        storage.close();

        // Then
        assertTrue(closed[0]);
    }

    @Test
    void testMultipleStoreOperations() throws Exception {
        // Given
        CheckpointStorage storage = new CheckpointStorage() {
            private final java.util.Map<Long, Checkpoint> checkpoints = new java.util.HashMap<>();

            @Override
            public void storeCheckpoint(Checkpoint checkpoint) {
                checkpoints.put(checkpoint.getCheckpointId(), checkpoint);
            }

            @Override
            public Checkpoint loadCheckpoint(long checkpointId) {
                return checkpoints.get(checkpointId);
            }

            @Override
            public Checkpoint getLatestCheckpoint() {
                return checkpoints.values().stream()
                    .max(java.util.Comparator.comparingLong(Checkpoint::getTimestamp))
                    .orElse(null);
            }

            @Override
            public List<Checkpoint> listCheckpoints(int limit) {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean deleteCheckpoint(long checkpointId) {
                return checkpoints.remove(checkpointId) != null;
            }

            @Override
            public int cleanupOldCheckpoints(int keepCount) {
                return 0;
            }

            @Override
            public void close() {
            }
        };

        // When - store same checkpoint multiple times (update)
        Checkpoint cp1 = new DefaultCheckpoint(1, 1000);
        Checkpoint cp1Updated = new DefaultCheckpoint(1, 1500);

        storage.storeCheckpoint(cp1);
        storage.storeCheckpoint(cp1Updated);

        // Then - should have the updated version
        assertEquals(1500, storage.loadCheckpoint(1).getTimestamp());
    }

    @Test
    void testMethodSignatures() throws NoSuchMethodException {
        // Given & When & Then - verify method signatures
        java.lang.reflect.Method storeMethod = CheckpointStorage.class.getMethod("storeCheckpoint", Checkpoint.class);
        assertTrue(java.lang.reflect.Modifier.isAbstract(storeMethod.getModifiers()));
        assertEquals(Exception.class, storeMethod.getExceptionTypes()[0]);

        java.lang.reflect.Method loadMethod = CheckpointStorage.class.getMethod("loadCheckpoint", long.class);
        assertEquals(Checkpoint.class, loadMethod.getReturnType());
        assertEquals(Exception.class, loadMethod.getExceptionTypes()[0]);

        java.lang.reflect.Method closeMethod = CheckpointStorage.class.getMethod("close");
        assertEquals(void.class, closeMethod.getReturnType());
    }
}
