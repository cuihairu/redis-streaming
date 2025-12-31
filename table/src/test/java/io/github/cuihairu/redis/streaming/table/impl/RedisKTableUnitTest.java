package io.github.cuihairu.redis.streaming.table.impl;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisKTableUnitTest {

    @Test
    void putUsesStringCodecAndJsonSerialization() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");

        verify(map).put(eq("\"k1\""), eq("\"v1\""));
    }

    @Test
    void getUsesStringCodecAndDeserializesJson() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.get(eq("\"k1\""))).thenReturn("\"v1\"");

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        assertEquals("v1", table.get("k1"));
        assertNull(table.get("missing"));
    }

    @Test
    void getStateDeserializesAllEntries() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        Map<String, String> state = table.getState();

        assertEquals(2, state.size());
        assertEquals("v1", state.get("k1"));
        assertEquals("v2", state.get("k2"));
    }
}
