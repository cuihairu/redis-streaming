package io.github.cuihairu.redis.streaming.sink.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisHashSinkTest {

    @SuppressWarnings("unchecked")
    private final RMap<String, String> map = mock(RMap.class);

    private RedissonClient redissonWithMap() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String, String>getMap(anyString())).thenReturn((RMap) map);
        return redisson;
    }

    @Test
    void writesStringDirectlyAndSerializesOthers() throws Exception {
        RedisHashSink<String, Object> sink = new RedisHashSink<>(redissonWithMap(), "h");
        sink.write("k", "plain");
        verify(map).put("k", "plain");
        sink.write("k2", Map.of("a", 1));
        verify(map).put(eq("k2"), argThat(s -> s instanceof String && s.contains("\"a\":1")));
        assertEquals("h", sink.getHashName());
    }

    @Test
    void appliesTtlWhenConfigured() {
        RedisHashSink<String, String> sink =
                new RedisHashSink<>(redissonWithMap(), "h", new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMinutes(5));
        sink.write("k", "v");
        verify(map).expire(Duration.ofMinutes(5));
    }

    @Test
    void batchDeleteAndSizeDelegate() {
        RedisHashSink<String, String> sink = new RedisHashSink<>(redissonWithMap(), "h");
        sink.writeBatch(Map.of("a", "1", "b", "2"));
        verify(map).putAll(any());

        when(map.remove("a")).thenReturn("text");
        assertEquals("text", sink.delete("a"));
        when(map.size()).thenReturn(4);
        assertEquals(4, sink.getHashSize());
        sink.clear();
        verify(map).clear();
        sink.deleteHash();
        verify(map).delete();
    }

    @Test
    void writeWithFieldTtlFallsBackToHashTtl() {
        // no sink-level ttl configured -> field TTL is a plain write (documented fallback)
        RedisHashSink<String, String> plain = new RedisHashSink<>(redissonWithMap(), "h");
        plain.writeWithFieldTTL("k", "v", Duration.ofSeconds(30));
        verify(map).put("k", "v");
        verify(map, never()).expire(any(Duration.class));

        // sink-level ttl present -> hash-level expire is (re)applied with the field ttl
        RedisHashSink<String, String> withTtl =
                new RedisHashSink<>(redissonWithMap(), "h", new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMinutes(1));
        withTtl.writeWithFieldTTL("k2", "v2", Duration.ofSeconds(30));
        verify(map, times(2)).expire(any(Duration.class)); // once from write(), once from the fallback
    }

    @Test
    void asyncWriteCompletes() throws Exception {
        RedisHashSink<String, String> sink = new RedisHashSink<>(redissonWithMap(), "h");
        sink.writeAsync("k", "v").get(5, java.util.concurrent.TimeUnit.SECONDS);
        verify(map).put("k", "v");
    }

    @Test
    void propagatesBackendFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> failing = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString())).thenReturn(failing);
        when(failing.put(anyString(), anyString())).thenThrow(new RuntimeException("down"));
        RedisHashSink<String, String> sink = new RedisHashSink<>(redisson, "h");
        assertThrows(RuntimeException.class, () -> sink.write("k", "v"));
    }

    @Test
    void validatesArguments() {
        assertThrows(NullPointerException.class, () -> new RedisHashSink<>(null, "h"));
        assertThrows(NullPointerException.class, () -> new RedisHashSink<>(mock(RedissonClient.class), null));
        RedisHashSink<String, String> sink = new RedisHashSink<>(redissonWithMap(), "h");
        assertThrows(NullPointerException.class, () -> sink.write(null, "v"));
        assertThrows(NullPointerException.class, () -> sink.write("k", null));
        assertThrows(NullPointerException.class, () -> sink.writeBatch(null));
    }
}
