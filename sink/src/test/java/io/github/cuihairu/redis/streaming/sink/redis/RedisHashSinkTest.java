package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisHashSinkTest {

    @Test
    void writeStoresConvertedKeyAndValueAndAppliesTtl() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("h")).thenReturn(map);

        RedisHashSink<String, String> sink = new RedisHashSink<>(redisson, "h", new ObjectMapper(), Duration.ofSeconds(5));
        sink.write("k", "v");

        verify(map).put("k", "v");
        verify(map).expire(Duration.ofSeconds(5));
        assertEquals("h", sink.getHashName());
    }

    @Test
    void writeSerializesNonStringValues() {
        record Event(int x) {}

        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("h")).thenReturn(map);

        RedisHashSink<String, Event> sink = new RedisHashSink<>(redisson, "h", new ObjectMapper(), null);
        sink.write("k", new Event(1));

        verify(map).put(eq("k"), argThat(s -> s.contains("\"x\":1")));
    }

    @Test
    void writeBatchConvertsEntriesAndExpires() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("h")).thenReturn(map);

        RedisHashSink<String, String> sink = new RedisHashSink<>(redisson, "h", new ObjectMapper(), Duration.ofSeconds(1));
        sink.writeBatch(Map.of("a", "1", "b", "2"));

        verify(map).putAll(Map.of("a", "1", "b", "2"));
        verify(map).expire(Duration.ofSeconds(1));
    }

    @Test
    void writeWithFieldTtlExpiresWithProvidedDurationWhenTtlConfigured() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("h")).thenReturn(map);

        RedisHashSink<String, String> sink = new RedisHashSink<>(redisson, "h", new ObjectMapper(), Duration.ofSeconds(1));
        sink.writeWithFieldTTL("k", "v", Duration.ofSeconds(9));

        verify(map).put("k", "v");
        verify(map).expire(Duration.ofSeconds(1));
        verify(map).expire(Duration.ofSeconds(9));
    }

    @Test
    void deleteReturnsParsedJsonWhenPossible() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("h")).thenReturn(map);

        when(map.remove("k")).thenReturn("\"hello\"");
        RedisHashSink<String, String> sink = new RedisHashSink<>(redisson, "h", new ObjectMapper(), null);
        assertEquals("hello", sink.delete("k"));
    }

    @Test
    void deleteReturnsRawValueWhenNotJson() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap("h")).thenReturn(map);

        when(map.remove("k")).thenReturn("not-json");
        RedisHashSink<String, String> sink = new RedisHashSink<>(redisson, "h", new ObjectMapper(), null);
        assertEquals("not-json", sink.delete("k"));
    }
}
