package io.github.cuihairu.redis.streaming.runtime.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the private stream-id helpers (parse/compare/truncate) and {@code DeferredAcks}.
 * These pure helpers sit behind checkpoint ack flows; several branches (null ids, unparsable
 * ids) are unreachable through Redis Stream ids alone, so they are driven reflectively.
 */
class RedisStreamIdHelpersTest {

    private static final String ENV = "io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment";

    @Test
    void parseStreamIdCoversAllInputShapes() throws Exception {
        assertEquals(new StreamMessageId(5, 1), parseStreamId("5-1"));
        assertEquals(new StreamMessageId(7), parseStreamId("7"));
        assertEquals(StreamMessageId.MIN, parseStreamId(null));
        assertEquals(StreamMessageId.MIN, parseStreamId("not-a-number"));
    }

    @Test
    void compareStreamIdCoversAllBranches() throws Exception {
        assertEquals(0, compareStreamId(null, null));
        assertEquals(-1, compareStreamId(null, "1-0"));
        assertEquals(1, compareStreamId("1-0", null));
        assertTrue(compareStreamId("5-1", "5-2") < 0);
        assertTrue(compareStreamId("5-2", "5-1") > 0);
        assertEquals(0, compareStreamId("5-1", "5-1"));
        assertTrue(compareStreamId("5", "5-1") < 0);
        assertTrue(compareStreamId("6-1", "5-9") > 0);
        assertTrue(compareStreamId("5-1", "5") > 0);
        assertEquals("zz".compareTo("yy"), compareStreamId("zz", "yy"));
    }

    @Test
    void truncateCoversAllBranches() throws Exception {
        Method truncate = Class.forName(ENV).getDeclaredMethod("truncate", String.class, int.class);
        truncate.setAccessible(true);
        assertNull(truncate.invoke(null, null, 5));
        assertEquals("", truncate.invoke(null, "abc", 0));
        assertEquals("abc", truncate.invoke(null, "abc", 10));
        assertEquals("abc", truncate.invoke(null, "abcdef", 3));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deferredAckSnapshotAndClearWork() throws Exception {
        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        record(deferred, "t", "g", 0, "5-3");
        record(deferred, "t", "g", 1, "9");
        record(deferred, "t", "g", 2, "bad-id");
        record(deferred, "t", "g", 3, "");

        Map<String, Map<Integer, String>> offsets = snapshotOffsets(deferred);
        assertEquals("5-3", offsets.get("t|g").get(0));
        assertEquals("9", offsets.get("t|g").get(1));
        assertEquals("bad-id", offsets.get("t|g").get(2));
        assertFalse(offsets.get("t|g").containsKey(3), "blank trailing ids are not committed");

        invoke(deferred, "clear");
        assertTrue(snapshotOffsets(deferred).isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ackAllParsesIdsAcksAndAdvancesFrontier() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RMap frontier = mock(RMap.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getMap(anyString())).thenReturn(frontier);
        when(frontier.get("g")).thenReturn(null);

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        record(deferred, "t", "g", 0, "7");
        record(deferred, "t", "g", 0, "nope");
        invoke(deferred, "ackAll", redisson);

        verify(stream).ack("g", new StreamMessageId(5, 1), new StreamMessageId(7), StreamMessageId.MIN);
        verify(frontier).put("g", "nope");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ackAllRespectsHigherExistingFrontier() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RMap frontier = mock(RMap.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getMap(anyString())).thenReturn(frontier);
        when(frontier.get("g")).thenReturn("999999-9");

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        invoke(deferred, "ackAll", redisson);

        verify(stream).ack("g", new StreamMessageId(5, 1));
        verify(frontier, never()).put(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ackAllFallsBackToLexicographicCompareOnGarbageFrontier() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RMap frontier = mock(RMap.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getMap(anyString())).thenReturn(frontier);
        when(frontier.get("g")).thenReturn("zzz");

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        invoke(deferred, "ackAll", redisson);

        verify(stream).ack("g", new StreamMessageId(5, 1));
        // "5-1".compareTo("zzz") is negative -> frontier keeps the garbage marker
        verify(frontier, never()).put(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ackAllToleratesAckAndFrontierErrorsAndNullClient() throws Exception {
        invoke(newDeferredAcks(), "ackAll", new Object[]{null});

        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RMap frontier = mock(RMap.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getMap(anyString())).thenReturn(frontier);
        when(frontier.get("g")).thenReturn("1-0");
        doThrow(new RuntimeException("ack down")).when(stream).ack(anyString(), any(StreamMessageId[].class));

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        assertDoesNotThrow(() -> invoke(deferred, "ackAll", redisson));
        verify(frontier).put("g", "5-1");
    }

    private static StreamMessageId parseStreamId(String id) throws Exception {
        Method m = Class.forName(ENV).getDeclaredMethod("parseStreamId", String.class);
        m.setAccessible(true);
        return (StreamMessageId) m.invoke(null, id);
    }

    private static int compareStreamId(String a, String b) throws Exception {
        Method m = Class.forName(ENV).getDeclaredMethod("compareStreamId", String.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, a, b);
    }

    private static Object newDeferredAcks() throws Exception {
        Class<?> clazz = Class.forName(ENV + "$DeferredAcks");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void record(Object deferred, String topic, String group, int partition, String id) throws Exception {
        Method m = deferred.getClass().getDeclaredMethod("record", String.class, String.class, int.class, String.class);
        m.setAccessible(true);
        m.invoke(deferred, topic, group, partition, id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<Integer, String>> snapshotOffsets(Object deferred) throws Exception {
        Method m = deferred.getClass().getDeclaredMethod("snapshotOffsets");
        m.setAccessible(true);
        return (Map<String, Map<Integer, String>>) m.invoke(deferred);
    }

    private static void invoke(Object target, String name, Object... args) throws Exception {
        if ("clear".equals(name)) {
            Method m = target.getClass().getDeclaredMethod("clear");
            m.setAccessible(true);
            m.invoke(target);
            return;
        }
        Method m = target.getClass().getDeclaredMethod(name, RedissonClient.class);
        m.setAccessible(true);
        if (args.length == 1 && args[0] == null) {
            m.invoke(target, new Object[]{null});
        } else {
            m.invoke(target, args);
        }
    }
}
