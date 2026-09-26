package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
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

    /** B-13: every freshly created checkpoint carries the current snapshot version. */
    @Test
    void newCheckpointsCarryTheCurrentSnapshotVersion() {
        assertEquals(DefaultCheckpoint.CURRENT_SNAPSHOT_VERSION, new DefaultCheckpoint(1, 1L).getSnapshotVersion());
        assertNotEquals(DefaultCheckpoint.LEGACY_SNAPSHOT_VERSION, new DefaultCheckpoint(1, 1L).getSnapshotVersion());
    }

    /**
     * B-13: the typed read returns the value as the requested type, converts a decoded
     * shape (map of fields) to the requested type instead of deferring a
     * ClassCastException to the call site, passes null through, and reports impossible
     * conversions with a message naming key and types.
     */
    @Test
    void typedStateReadChecksAndConvertsAtTheReadSite() {
        DefaultCheckpoint checkpoint = new DefaultCheckpoint(1, 1L);
        Checkpoint.StateSnapshot snapshot = checkpoint.getStateSnapshot();

        Widget widget = new Widget("gear", 7);
        snapshot.putState("widget", widget);
        snapshot.putState("widgetFields", Map.of("name", "cog", "count", 3));
        snapshot.putState("count", 42);

        assertSame(widget, snapshot.getState("widget", Widget.class));
        Widget converted = snapshot.getState("widgetFields", Widget.class);
        assertEquals("cog", converted.getName());
        assertEquals(3, converted.getCount());

        assertNull(snapshot.getState("missing", Widget.class));
        assertEquals(42, (Integer) snapshot.getState("count", Integer.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> snapshot.getState("count", Widget.class));
        assertTrue(ex.getMessage().contains("count"));
        assertTrue(ex.getMessage().contains("java.lang.Integer"));
        assertTrue(ex.getMessage().contains(Widget.class.getName()));
    }

    @Test
    void legacyCheckpointsReportVersionZero() {
        // an implementation written before versioning exists has no marker at all
        Checkpoint legacy = new Checkpoint() {
            @Override
            public long getCheckpointId() {
                return 1;
            }

            @Override
            public long getTimestamp() {
                return 1;
            }

            @Override
            public StateSnapshot getStateSnapshot() {
                return new StateSnapshot() {
                    @Override
                    public <T> T getState(String key) {
                        return null;
                    }

                    @Override
                    public <T> void putState(String key, T value) {
                    }

                    @Override
                    public Iterable<String> getKeys() {
                        return java.util.List.of();
                    }
                };
            }

            @Override
            public boolean isCompleted() {
                return true;
            }

            @Override
            public void markCompleted() {
            }
        };
        assertEquals(0, legacy.getSnapshotVersion(),
                "implementations without a version marker must read as legacy");
    }

    /** Simple state POJO used to exercise typed reads. */
    public static class Widget {
        private String name;
        private int count;

        public Widget() {
        }

        public Widget(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }
}

