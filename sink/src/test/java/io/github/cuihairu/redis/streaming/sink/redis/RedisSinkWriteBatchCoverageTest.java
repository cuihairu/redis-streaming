package io.github.cuihairu.redis.streaming.sink.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers RedisHashSink writeBatch/delete and RedisStreamSink write edge branches. */
class RedisSinkWriteBatchCoverageTest {

    private RedissonClient redisson;
    private RMap<String, String> map;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString())).thenReturn(map);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeBatchConvertsAndBulkWrites() {
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(new LinkedHashMap<>(inv.getArgument(0)));
            return null;
        }).when(map).putAll(any(Map.class));

        RedisHashSink<String, Integer> sink = new RedisHashSink<>(redisson, "h1");
        Map<String, Integer> entries = new LinkedHashMap<>();
        entries.put("k1", 1);
        entries.put("k2", 2);
        sink.writeBatch(entries);

        assertTrue(captured.get().containsKey("k1"));
        assertEquals("2", captured.get().get("k2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeBatchPropagatesFailures() {
        doThrow(new IllegalStateException("redis gone")).when(map).putAll(any(Map.class));
        RedisHashSink<String, Integer> sink = new RedisHashSink<>(redisson, "h1");
        assertThrows(RuntimeException.class, () -> sink.writeBatch(Map.of("k", 1)));
        assertThrows(NullPointerException.class, () -> sink.writeBatch(null));
    }

    @Test
    void deleteReturnsPreviousValueOrRawOrNull() {
        RedisHashSink<String, Object> sink = new RedisHashSink<>(redisson, "h2");

        when(map.remove("k")).thenReturn(null);
        assertNull(sink.delete("k"));

        when(map.remove("k")).thenReturn("plain-text");
        assertEquals("plain-text", sink.delete("k"));

        when(map.remove("k")).thenReturn("{\"v\":1}");
        Object parsed = sink.delete("k");
        assertTrue(parsed instanceof Map, "json payloads are parsed: " + parsed);
    }

    @Test
    void deletePropagatesFailures() {
        when(map.remove(anyString())).thenThrow(new IllegalStateException("redis gone"));
        RedisHashSink<String, Object> sink = new RedisHashSink<>(redisson, "h2");
        assertThrows(RuntimeException.class, () -> sink.delete("k"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamSinkWriteHandlesStringAndObjectPayloads() {
        RStream<String, String> stream = mock(RStream.class);
        when(redisson.<String, String>getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(1, 0));

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "st");
        assertTrue(sink.write("raw"));
        assertTrue(sink.write("obj"));
        verify(stream, org.mockito.Mockito.times(2)).add(any());

        when(stream.add(any())).thenReturn(null);
        assertFalse(sink.write("x"), "null stream id reports failure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamSinkWritePropagatesFailures() {
        RStream<String, String> stream = mock(RStream.class);
        when(redisson.<String, String>getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(stream);
        when(stream.add(any())).thenThrow(new IllegalStateException("redis gone"));

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "st");
        assertThrows(RuntimeException.class, () -> sink.write("x"));
    }
}
