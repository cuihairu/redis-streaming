package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
    void ackAllParsesIdsAcksAndHandsFrontierToAtomicScript() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        record(deferred, "t", "g", 0, "7");
        record(deferred, "t", "g", 0, "nope");
        invoke(deferred, "ackAll", redisson);

        verify(stream).ack("g", new StreamMessageId(5, 1), new StreamMessageId(7), StreamMessageId.MIN);
        // MQ-11: the raw max id is handed to the atomic Lua CAS — unparseable new ids
        // are rejected server-side, and no read-modify-write getMap/put path remains
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LONG),
                eq(java.util.Collections.singletonList(StreamKeys.commitFrontier("t", 0))), eq("g"), eq("nope"));
        verify(redisson, never()).getMap(anyString());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ackAllSendsEachPartitionFrontierThroughTheCasScript() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        record(deferred, "t", "g", 1, "9-2");
        record(deferred, "u", "h", 0, "1-0");
        invoke(deferred, "ackAll", redisson);

        // prev-frontier handling (higher existing id, garbage value) lives inside the
        // Lua script now — ackAll just hands every partition's max id to it
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LONG),
                eq(java.util.Collections.singletonList(StreamKeys.commitFrontier("t", 0))), eq("g"), eq("5-1"));
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LONG),
                eq(java.util.Collections.singletonList(StreamKeys.commitFrontier("t", 1))), eq("g"), eq("9-2"));
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LONG),
                eq(java.util.Collections.singletonList(StreamKeys.commitFrontier("u", 0))), eq("h"), eq("1-0"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ackAllToleratesAckAndScriptErrorsAndNullClient() throws Exception {
        invoke(newDeferredAcks(), "ackAll", new Object[]{null});

        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(redisson.<String, Object>getStream(anyString(), any())).thenReturn(stream);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        doThrow(new RuntimeException("ack down")).when(stream).ack(anyString(), any(StreamMessageId[].class));

        Object deferred = newDeferredAcks();
        record(deferred, "t", "g", 0, "5-1");
        assertDoesNotThrow(() -> invoke(deferred, "ackAll", redisson));
        // ack failure must not block the frontier hand-off
        verify(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LONG),
                eq(java.util.Collections.singletonList(StreamKeys.commitFrontier("t", 0))), eq("g"), eq("5-1"));

        // a script failure is swallowed too (best-effort frontier update)
        doThrow(new RuntimeException("script down")).when(script).eval(any(RScript.Mode.class), anyString(),
                any(RScript.ReturnType.class), anyList(), any(Object.class));
        record(deferred, "t", "g", 0, "6-0");
        assertDoesNotThrow(() -> invoke(deferred, "ackAll", redisson));
    }

    private static StreamMessageId parseStreamId(String id) throws Exception {
        Method m = Class.forName(ENV).getDeclaredMethod("parseStreamId", String.class);
        m.setAccessible(true);
        return (StreamMessageId) m.invoke(null, id);
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
