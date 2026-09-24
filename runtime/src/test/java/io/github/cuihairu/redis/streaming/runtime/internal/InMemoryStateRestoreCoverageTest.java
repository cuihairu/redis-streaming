package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link InMemoryKeyedStateStore#restoreFromSnapshot(Object)} branch paths and the
 * "register store after restore" path of {@link InMemoryCheckpointCoordinator#registerStore}.
 */
class InMemoryStateRestoreCoverageTest {

    @Test
    void restoreFromSnapshotNullClearsState() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        store.put("n", "k", "v");
        store.restoreFromSnapshot(null);
        assertNull(store.get("n", "k"));
    }

    @Test
    void restoreFromSnapshotRejectsNonMap() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.restoreFromSnapshot("not-a-map"));
        assertTrue(e.getMessage().contains("Unsupported snapshot type"));
    }

    @Test
    void restoreFromSnapshotSkipsNonStringStateNames() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        Map<Object, Object> raw = new HashMap<>();
        raw.put(42, Map.of("k", "v"));
        raw.put("ok", Map.of("k", "v2"));
        store.restoreFromSnapshot(raw);
        assertNull(store.get("42", "k"));
        assertEquals("v2", store.get("ok", "k"));
    }

    @Test
    void restoreFromSnapshotTreatsNonMapStateValueAsEmpty() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        store.put("n", "k", "old");
        Map<Object, Object> raw = new HashMap<>();
        raw.put("n", "scalar");
        store.restoreFromSnapshot(raw);
        assertNull(store.get("n", "k"));
    }

    @Test
    void restoreFromSnapshotRestoresNestedEntries() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        Map<Object, Object> inner = new HashMap<>();
        inner.put("k1", "v1");
        inner.put("k2", 5);
        Map<Object, Object> raw = new HashMap<>();
        raw.put("n", inner);
        store.restoreFromSnapshot(raw);
        assertEquals("v1", store.get("n", "k1"));
        assertEquals(5, store.get("n", "k2"));
    }

    @Test
    void registerStoreAppliesRestoredSnapshotWhenStoreIdIsReused() throws Exception {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();
        InMemoryKeyedStateStore<String> first = new InMemoryKeyedStateStore<>();
        coordinator.registerStore(first);
        first.put("n", "k", "v");
        long checkpointId = coordinator.triggerCheckpoint();
        coordinator.restoreFromCheckpoint(checkpointId);

        // Replaying store ids is what makes registerStore restore eagerly; the id sequence is
        // monotonic in production, so rewind it here to cover that branch deterministically.
        Field nextStoreId = InMemoryCheckpointCoordinator.class.getDeclaredField("nextStoreId");
        nextStoreId.setAccessible(true);
        ((AtomicLong) nextStoreId.get(coordinator)).set(1L);

        InMemoryKeyedStateStore<String> second = new InMemoryKeyedStateStore<>();
        String storeId = coordinator.registerStore(second);
        assertEquals("store-1", storeId);
        assertEquals("v", second.get("n", "k"));
        assertThrows(NullPointerException.class, () -> coordinator.registerStore(null));
    }
}
