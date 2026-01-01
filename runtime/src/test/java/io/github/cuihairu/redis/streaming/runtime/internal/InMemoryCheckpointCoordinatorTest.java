package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryCheckpointCoordinator
 */
class InMemoryCheckpointCoordinatorTest {

    private InMemoryCheckpointCoordinator coordinator;
    private InMemoryKeyedStateStore<String> store1;
    private InMemoryKeyedStateStore<Integer> store2;

    @BeforeEach
    void setUp() {
        coordinator = new InMemoryCheckpointCoordinator();
        store1 = new InMemoryKeyedStateStore<>();
        store2 = new InMemoryKeyedStateStore<>();
    }

    @Test
    void testRegisterStore() {
        // When
        String storeId = coordinator.registerStore(store1);

        // Then
        assertNotNull(storeId);
        assertTrue(storeId.startsWith("store-"));
        assertEquals(1, coordinator.getRegisteredStores().size());
        assertTrue(coordinator.getRegisteredStores().containsValue(store1));
    }

    @Test
    void testRegisterMultipleStores() {
        // When
        String storeId1 = coordinator.registerStore(store1);
        String storeId2 = coordinator.registerStore(store2);

        // Then
        assertNotEquals(storeId1, storeId2);
        assertEquals(2, coordinator.getRegisteredStores().size());
        assertTrue(coordinator.getRegisteredStores().containsValue(store1));
        assertTrue(coordinator.getRegisteredStores().containsValue(store2));
    }

    @Test
    void testRegisterStoreWithNullThrowsException() {
        // When & Then
        assertThrows(NullPointerException.class, () -> coordinator.registerStore(null));
    }

    @Test
    void testTriggerCheckpoint() {
        // Given
        coordinator.registerStore(store1);
        store1.put("state1", "key1", "value1");

        // When
        long checkpointId = coordinator.triggerCheckpoint();

        // Then
        assertTrue(checkpointId > 0);
        assertNotNull(coordinator.getCheckpoint(checkpointId));
        assertEquals(checkpointId, coordinator.getCheckpoint(checkpointId).getCheckpointId());
    }

    @Test
    void testTriggerMultipleCheckpoints() {
        // Given
        coordinator.registerStore(store1);

        // When
        long id1 = coordinator.triggerCheckpoint();
        long id2 = coordinator.triggerCheckpoint();
        long id3 = coordinator.triggerCheckpoint();

        // Then
        assertTrue(id1 < id2);
        assertTrue(id2 < id3);
        assertNotNull(coordinator.getCheckpoint(id1));
        assertNotNull(coordinator.getCheckpoint(id2));
        assertNotNull(coordinator.getCheckpoint(id3));
    }

    @Test
    void testTriggerCheckpointCapturesState() {
        // Given
        String storeId = coordinator.registerStore(store1);
        store1.setCurrentKey("key1");
        store1.put("state1", "key1", "value1");
        store1.put("state1", "key2", "value2");

        // When
        long checkpointId = coordinator.triggerCheckpoint();

        // Then
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        
        // Check if storeId is in the keys
        boolean found = false;
        for (String key : checkpoint.getStateSnapshot().getKeys()) {
            if (key.equals(storeId)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Store ID should be in checkpoint keys");
    }

    @Test
    void testGetLatestCheckpoint() {
        // Given
        coordinator.registerStore(store1);
        coordinator.triggerCheckpoint();
        coordinator.triggerCheckpoint();
        long lastId = coordinator.triggerCheckpoint();

        // When
        Checkpoint latest = coordinator.getLatestCheckpoint();

        // Then
        assertNotNull(latest);
        assertEquals(lastId, latest.getCheckpointId());
    }

    @Test
    void testGetLatestCheckpointReturnsNullWhenNoCheckpoint() {
        // When
        Checkpoint latest = coordinator.getLatestCheckpoint();

        // Then
        assertNull(latest);
    }

    @Test
    void testGetCheckpoint() {
        // Given
        coordinator.registerStore(store1);
        long checkpointId = coordinator.triggerCheckpoint();

        // When
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);

        // Then
        assertNotNull(checkpoint);
        assertEquals(checkpointId, checkpoint.getCheckpointId());
    }

    @Test
    void testGetCheckpointReturnsNullForNonExistent() {
        // When
        Checkpoint checkpoint = coordinator.getCheckpoint(999);

        // Then
        assertNull(checkpoint);
    }

    @Test
    void testRestoreFromCheckpoint() {
        // Given
        coordinator.registerStore(store1);
        store1.put("state1", "key1", "value1");
        store1.put("state1", "key2", "value2");
        long checkpointId = coordinator.triggerCheckpoint();

        // Clear the store
        store1.setCurrentKey("key1");
        store1.put("state1", "key1", "modified");

        // When
        coordinator.restoreFromCheckpoint(checkpointId);

        // Then
        store1.setCurrentKey("key1");
        assertEquals("value1", store1.get("state1", "key1"));
        assertEquals("value2", store1.get("state1", "key2"));
    }

    @Test
    void testRestoreFromNonExistentCheckpointThrowsException() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> coordinator.restoreFromCheckpoint(999));
    }

    @Test
    void testRestoreFromCheckpointWithMultipleStores() {
        // Given
        coordinator.registerStore(store1);
        coordinator.registerStore(store2);

        store1.put("state1", "key1", "value1");
        store2.put("state2", 100, 200);

        long checkpointId = coordinator.triggerCheckpoint();

        // Modify stores
        store1.put("state1", "key1", "modified");
        store2.put("state2", 100, 999);

        // When
        coordinator.restoreFromCheckpoint(checkpointId);

        // Then
        store1.setCurrentKey("key1");
        assertEquals("value1", store1.get("state1", "key1"));
        assertEquals(Integer.valueOf(200), store2.get("state2", 100));
    }

    @Test
    void testRestoreFromCheckpointWithExistingStore() {
        // Given
        String storeId = coordinator.registerStore(store1);
        store1.put("state1", "key1", "value1");
        long checkpointId = coordinator.triggerCheckpoint();

        // Modify the store
        store1.put("state1", "key1", "modified");

        // When - restore from checkpoint
        coordinator.restoreFromCheckpoint(checkpointId);

        // Then - store should be restored to checkpoint state
        store1.setCurrentKey("key1");
        assertEquals("value1", store1.get("state1", "key1"));
    }

    @Test
    void testAcknowledgeCheckpoint() {
        // Given
        coordinator.registerStore(store1);
        long checkpointId = coordinator.triggerCheckpoint();

        // When & Then - should not throw (single-threaded runtime completes synchronously)
        assertDoesNotThrow(() -> coordinator.acknowledgeCheckpoint(checkpointId, "task-1"));
    }

    @Test
    void testCompleteCheckpoint() {
        // Given
        coordinator.registerStore(store1);
        long checkpointId = coordinator.triggerCheckpoint();

        // When & Then - should not throw (single-threaded runtime completes synchronously)
        assertDoesNotThrow(() -> coordinator.completeCheckpoint(checkpointId));
    }

    @Test
    void testCheckpointWithEmptyState() {
        // Given
        coordinator.registerStore(store1);

        // When
        long checkpointId = coordinator.triggerCheckpoint();

        // Then
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        assertNotNull(checkpoint);
        assertNotNull(checkpoint.getStateSnapshot());
    }

    @Test
    void testCheckpointIdIncrements() {
        // Given
        coordinator.registerStore(store1);

        // When
        long id1 = coordinator.triggerCheckpoint();
        long id2 = coordinator.triggerCheckpoint();

        // Then
        assertEquals(1, id2 - id1);
    }

    @Test
    void testStoreIdIncrements() {
        // When
        String id1 = coordinator.registerStore(store1);
        String id2 = coordinator.registerStore(store2);

        // Then
        assertTrue(id2.compareTo(id1) > 0);
    }

    @Test
    void testGetRegisteredStoresReturnsUnmodifiableMap() {
        // Given
        coordinator.registerStore(store1);

        // When
        Map<String, InMemoryKeyedStateStore<?>> stores = coordinator.getRegisteredStores();

        // Then - modifying the returned map should not affect coordinator
        assertThrows(UnsupportedOperationException.class, () -> stores.put("new", store2));
    }

    @Test
    void testCheckpointAfterRestore() {
        // Given
        coordinator.registerStore(store1);
        store1.put("state1", "key1", "value1");
        long checkpointId1 = coordinator.triggerCheckpoint();

        // Restore
        coordinator.restoreFromCheckpoint(checkpointId1);

        // Modify and create new checkpoint
        store1.put("state1", "key2", "value2");
        long checkpointId2 = coordinator.triggerCheckpoint();

        // Then - new checkpoint should reflect the modified state
        assertNotEquals(checkpointId1, checkpointId2);
        assertEquals(checkpointId2, coordinator.getLatestCheckpoint().getCheckpointId());
    }

    @Test
    void testRestoreClearsPreviousRestoredState() {
        // Given
        coordinator.registerStore(store1);
        store1.put("state1", "key1", "value1");
        long checkpoint1 = coordinator.triggerCheckpoint();

        store1.put("state1", "key2", "value2");
        long checkpoint2 = coordinator.triggerCheckpoint();

        // When - restore from first checkpoint
        coordinator.restoreFromCheckpoint(checkpoint1);

        // Then - should have state from first checkpoint
        store1.setCurrentKey("key1");
        assertEquals("value1", store1.get("state1", "key1"));
        assertNull(store1.get("state1", "key2"));
    }

    @Test
    void testTriggerCheckpointWithNoRegisteredStores() {
        // When & Then - should work even with no stores
        long checkpointId = coordinator.triggerCheckpoint();

        assertNotNull(coordinator.getCheckpoint(checkpointId));
    }

    @Test
    void testConsecutiveCheckpointsCaptureChangingState() {
        // Given
        coordinator.registerStore(store1);

        // First checkpoint
        store1.put("state1", "key1", "value1");
        long cp1 = coordinator.triggerCheckpoint();

        // Modify state
        store1.put("state1", "key1", "value2");
        long cp2 = coordinator.triggerCheckpoint();

        // Verify we can restore to different states
        coordinator.restoreFromCheckpoint(cp1);
        store1.setCurrentKey("key1");
        assertEquals("value1", store1.get("state1", "key1"));

        coordinator.restoreFromCheckpoint(cp2);
        store1.setCurrentKey("key1");
        assertEquals("value2", store1.get("state1", "key1"));
    }
}
