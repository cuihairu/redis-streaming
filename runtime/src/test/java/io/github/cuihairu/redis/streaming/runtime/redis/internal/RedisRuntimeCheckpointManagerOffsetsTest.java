package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression tests for RT-H1: offset snapshots are built with {@code Integer} partition keys,
 * but {@code RedisCheckpointStorage} serializes through the default Jackson codec, which
 * stringifies map keys. The plain {@code get(pid)} lookup on the restored map missed every
 * entry, silently rewinding every restored consumer group to {@code 0-0}.
 */
class RedisRuntimeCheckpointManagerOffsetsTest {

    @SuppressWarnings("unchecked")
    private static String offsetFor(Map<Integer, String> perPartition, int pid) throws Exception {
        Method m = RedisRuntimeCheckpointManager.class
                .getDeclaredMethod("offsetForPartition", Map.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, perPartition, pid);
    }

    @Test
    void lookupFindsOffsetsInStringKeyedRestoredMap() throws Exception {
        // Exactly what Jackson gives back after the checkpoint round-trip.
        Map<Integer, String> stringKeyed = new HashMap<>();
        ((Map<Object, Object>) (Map<?, ?>) stringKeyed).put("3", "1700000000000-5");
        ((Map<Object, Object>) (Map<?, ?>) stringKeyed).put("7", "1700000000001-0");

        assertEquals("1700000000000-5", offsetFor(stringKeyed, 3));
        assertEquals("1700000000001-0", offsetFor(stringKeyed, 7));
    }

    @Test
    void lookupStillFindsOffsetsInInProcessIntegerKeyedMap() throws Exception {
        Map<Integer, String> intKeyed = new HashMap<>();
        intKeyed.put(3, "1-1");

        assertEquals("1-1", offsetFor(intKeyed, 3));
    }

    @Test
    void missingPartitionsStayNullSoRestoreFallsBackToZeroZero() throws Exception {
        Map<Integer, String> stringKeyed = new HashMap<>();
        ((Map<Object, Object>) (Map<?, ?>) stringKeyed).put("3", "1700000000000-5");

        assertNull(offsetFor(stringKeyed, 4));
        assertNull(offsetFor(new HashMap<>(), 0));
    }

    @Test
    void stringKeyedLookupWinsOverNothingWhenBothKeyStylesCoexist() throws Exception {
        Map<Integer, String> mixed = new HashMap<>();
        mixed.put(3, "int-key-value");
        ((Map<Object, Object>) (Map<?, ?>) mixed).put("3", "string-key-value");

        assertEquals("int-key-value", offsetFor(mixed, 3), "Integer keys take precedence");
    }
}
