package io.github.cuihairu.redis.streaming.api.checkpoint;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the executable default methods of the {@link Checkpoint} API:
 * {@code getSnapshotVersion()} (B-13: a legacy implementation that does not override it
 * must read as version 0) and {@code StateSnapshot.getState(key, type)} (B-13: the typed
 * read must pass matching values through, pass absent keys through as null, and fail a
 * mismatched type here with a descriptive exception instead of a distant
 * ClassCastException).
 */
class CheckpointDefaultMethodsTest {

    /** Minimal in-memory {@link StateSnapshot} without overriding any default method. */
    static class MapStateSnapshot implements Checkpoint.StateSnapshot {
        final Map<String, Object> store = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getState(String key) {
            return (T) store.get(key);
        }

        @Override
        public <T> void putState(String key, T value) {
            store.put(key, value);
        }

        @Override
        public Iterable<String> getKeys() {
            return List.copyOf(store.keySet());
        }
    }

    /** Minimal {@link Checkpoint} without overriding any default method. */
    static class BareCheckpoint implements Checkpoint {
        final MapStateSnapshot snapshot = new MapStateSnapshot();
        boolean completed;

        @Override
        public long getCheckpointId() {
            return 7L;
        }

        @Override
        public long getTimestamp() {
            return 1_000L;
        }

        @Override
        public StateSnapshot getStateSnapshot() {
            return snapshot;
        }

        @Override
        public boolean isCompleted() {
            return completed;
        }

        @Override
        public void markCompleted() {
            completed = true;
        }
    }

    @Test
    void legacyImplementationsReadAsSnapshotVersionZero() {
        BareCheckpoint cp = new BareCheckpoint();
        assertEquals(0, cp.getSnapshotVersion(),
                "B-13: an impl without a version marker must read as version 0 (legacy)");
    }

    @Test
    void typedGetStatePassesThroughMatchingValues() {
        BareCheckpoint cp = new BareCheckpoint();
        cp.getStateSnapshot().putState("count", 42);

        Integer value = cp.getStateSnapshot().getState("count", Integer.class);

        assertEquals(42, value);
    }

    @Test
    void typedGetStateReturnsNullForAbsentKeysEvenWithExpectedType() {
        BareCheckpoint cp = new BareCheckpoint();

        assertNull(cp.getStateSnapshot().getState("missing", String.class),
                "an absent key must stay null regardless of the requested type");
    }

    @Test
    void typedGetStateFailsDescriptivelyOnTypeMismatch() {
        BareCheckpoint cp = new BareCheckpoint();
        cp.getStateSnapshot().putState("flag", Boolean.TRUE);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cp.getStateSnapshot().getState("flag", String.class));

        assertTrue(ex.getMessage().contains("java.lang.Boolean")
                        && ex.getMessage().contains("java.lang.String")
                        && ex.getMessage().contains("'flag'"),
                "the exception must name the state key and both types: " + ex.getMessage());
    }

    @Test
    void bareCheckpointLifecycleDelegatesToTheImplementation() {
        BareCheckpoint cp = new BareCheckpoint();
        assertTrue(cp.getStateSnapshot().getKeys() instanceof Iterable);
        assertFalse(cp.isCompleted());
        cp.markCompleted();
        assertTrue(cp.isCompleted());
        assertEquals(7L, cp.getCheckpointId());
        assertEquals(1_000L, cp.getTimestamp());
    }
}
