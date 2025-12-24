package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultCheckpointTest {

    @Test
    void exposesBasicMetadataAndCompletion() {
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(7, 1234L);

        assertEquals(7, checkpoint.getCheckpointId());
        assertEquals(1234L, checkpoint.getTimestamp());
        assertFalse(checkpoint.isCompleted());

        checkpoint.markCompleted();
        assertTrue(checkpoint.isCompleted());
        assertTrue(checkpoint.toString().contains("id=7"));
    }

    @Test
    void snapshotStoresKeysValuesAndSupportsClear() {
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(1, 1L);
        Checkpoint.StateSnapshot snapshot = checkpoint.getStateSnapshot();
        DefaultCheckpoint.StateSnapshotImpl impl = (DefaultCheckpoint.StateSnapshotImpl) snapshot;

        assertEquals(0, impl.size());

        snapshot.putState("a", 1);
        snapshot.putState("b", "x");

        assertEquals(2, impl.size());
        assertEquals(1, (Integer) snapshot.getState("a"));
        assertEquals("x", snapshot.getState("b"));

        Set<String> keys = new HashSet<>();
        snapshot.getKeys().forEach(keys::add);
        assertEquals(Set.of("a", "b"), keys);

        impl.clear();
        assertEquals(0, impl.size());
        keys.clear();
        snapshot.getKeys().forEach(keys::add);
        assertTrue(keys.isEmpty());
    }
}

