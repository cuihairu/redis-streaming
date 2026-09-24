package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigCenter;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers {@code RedisConfigCenter} construction overloads and {@code getConfigMetadata} accessors. */
class RedisConfigCenterMetadataCoverageTest {

    private RedissonClient redisson;
    private RMap<String, String> map;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
    }

    @Test
    void constructorWithNullConfigUsesDefaults() {
        RedisConfigCenter center = new RedisConfigCenter(redisson, null);
        assertNotNull(center);
        assertFalse(center.isRunning());
    }

    @Test
    void metadataReflectsStoredEntry() {
        RedisConfigCenter center = new RedisConfigCenter(redisson, new ConfigServiceConfig("meta", true));
        center.start();

        Map<String, String> entry = new HashMap<>();
        entry.put("content", "abc");
        entry.put("version", "v1");
        entry.put("description", "d");
        entry.put("createTime", "1700000000000");
        entry.put("updateTime", "1700000001000");
        when(map.readAllMap()).thenReturn(entry);

        ConfigCenter.ConfigMetadata meta = center.getConfigMetadata("d1", "g1");
        assertEquals("v1", meta.getVersion());
        assertEquals("d", meta.getDescription());
        assertEquals(1700000000000L, meta.getCreateTime());
        assertEquals(1700000001000L, meta.getLastModified());
        assertEquals(3, meta.getSize());
    }

    @Test
    void metadataOfMissingEntryReturnsZeroDefaults() {
        RedisConfigCenter center = new RedisConfigCenter(redisson, new ConfigServiceConfig("meta", true));
        center.start();
        when(map.readAllMap()).thenReturn(new HashMap<>());

        ConfigCenter.ConfigMetadata meta = center.getConfigMetadata("missing", "g1");
        assertEquals(0L, meta.getCreateTime());
        assertEquals(0L, meta.getLastModified());
        assertEquals(0L, meta.getSize());
    }

    @Test
    void metadataFallsBackWhenRedisReadFails() {
        RedisConfigCenter center = new RedisConfigCenter(redisson, new ConfigServiceConfig("meta", true));
        center.start();
        when(map.readAllMap()).thenThrow(new IllegalStateException("redis gone"));

        ConfigCenter.ConfigMetadata meta = center.getConfigMetadata("d1", "g1");
        assertNotNull(meta);
        assertEquals(0L, meta.getSize());
    }

    @Test
    void metadataRequiresRunningCenter() {
        RedisConfigCenter center = new RedisConfigCenter(redisson, new ConfigServiceConfig("meta", true));
        assertThrows(IllegalStateException.class, () -> center.getConfigMetadata("d1", "g1"));
    }

    @Test
    void hasConfigUsesGetConfig() {
        RedisConfigCenter center = new RedisConfigCenter(redisson, new ConfigServiceConfig("meta", true));
        center.start();
        when(map.get("content")).thenReturn("present");
        assertTrue(center.hasConfig("d1", "g1"));

        when(map.get("content")).thenThrow(new IllegalStateException("redis gone"));
        assertFalse(center.hasConfig("d2", "g1"));
    }
}
