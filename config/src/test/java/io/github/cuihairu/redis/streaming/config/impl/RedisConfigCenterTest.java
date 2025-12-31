package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigCenter;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisConfigCenterTest {

    @Test
    void getConfigMetadataRequiresStart() {
        RedissonClient client = mock(RedissonClient.class);
        RedisConfigCenter center = new RedisConfigCenter(client, new ConfigServiceConfig());

        assertThrows(IllegalStateException.class, () -> center.getConfigMetadata("d1", "g1"));
    }

    @Test
    void getConfigMetadataReadsFromRedisHash() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);

        Map<String, String> fields = new HashMap<>();
        fields.put("content", "你好");
        fields.put("version", "v-123");
        fields.put("description", "desc");
        fields.put("createTime", "1000");
        fields.put("updateTime", "2000");

        when(client.getMap(anyString(), any(Codec.class))).thenReturn((RMap) map);
        when(map.readAllMap()).thenReturn(fields);

        RedisConfigCenter center = new RedisConfigCenter(client, new ConfigServiceConfig());
        center.start();
        try {
            ConfigCenter.ConfigMetadata metadata = center.getConfigMetadata("d1", "g1");
            assertNotNull(metadata);
            assertEquals("v-123", metadata.getVersion());
            assertEquals("desc", metadata.getDescription());
            assertEquals(1000L, metadata.getCreateTime());
            assertEquals(2000L, metadata.getLastModified());
            assertEquals("你好".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, metadata.getSize());
        } finally {
            center.stop();
        }
    }

    @Test
    void getConfigMetadataReturnsEmptyForMissingConfig() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);

        when(client.getMap(anyString(), any(Codec.class))).thenReturn((RMap) map);
        when(map.readAllMap()).thenReturn(java.util.Collections.emptyMap());

        RedisConfigCenter center = new RedisConfigCenter(client, new ConfigServiceConfig());
        center.start();
        try {
            ConfigCenter.ConfigMetadata metadata = center.getConfigMetadata("d1", "g1");
            assertNotNull(metadata);
            assertNull(metadata.getVersion());
            assertNull(metadata.getDescription());
            assertEquals(0L, metadata.getCreateTime());
            assertEquals(0L, metadata.getLastModified());
            assertEquals(0L, metadata.getSize());
        } finally {
            center.stop();
        }
    }
}
