package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigCenter;
import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

    /**
     * The facade has no injection seam (it builds the internal RedisConfigService in its
     * constructor), so the delegation methods are driven through mocked Redis plumbing —
     * each facade call must produce the corresponding real effect via the internal service.
     */
    @Test
    @SuppressWarnings("unchecked")
    void facadeDelegatesDataAndListenerCallsToTheInternalService() {
        RedissonClient client = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        RList<String> historyList = mock(RList.class);
        RScript script = mock(RScript.class);
        RTopic topic = mock(RTopic.class);
        RSet<String> subscribers = mock(RSet.class);

        when(client.getMap(anyString(), any(Codec.class))).thenReturn((RMap) map);
        when(client.getList(anyString(), any(Codec.class))).thenReturn((RList) historyList);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(client.getTopic(anyString(), any(Codec.class))).thenReturn(topic);
        when(client.<String>getSet(anyString())).thenReturn(subscribers);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);
        when(map.get("content")).thenReturn("cfg-value");
        when(map.readAllMap()).thenReturn(Map.of("content", "cfg-value", "version", "v-9"));
        when(historyList.size()).thenReturn(1);
        when(historyList.range(0, 0)).thenReturn(List.of(
                "{\"dataId\":\"d1\",\"group\":\"g1\",\"content\":\"old\",\"version\":\"v0\","
                        + "\"operation\":\"UPDATED\",\"operator\":\"system\",\"changeTime\":1700000000000}"));

        RedisConfigCenter center = new RedisConfigCenter(client, new ConfigServiceConfig());
        center.start();
        try {
            assertEquals("cfg-value", center.getConfig("d1", "g1"));

            assertTrue(center.publishConfig("d1", "g1", "cfg-value"));
            assertTrue(center.publishConfig("d1", "g1", "cfg-value", "desc"));
            assertTrue(center.removeConfig("d1", "g1"));

            List<ConfigHistory> history = center.getConfigHistory("d1", "g1", 5);
            assertEquals(1, history.size());
            assertEquals("old", history.get(0).getContent());
            assertEquals("v0", history.get(0).getVersion());

            AtomicReference<String> notified = new AtomicReference<>();
            ConfigChangeListener listener = (id, g, c, v) -> notified.set(c);
            center.addListener("d1", "g1", listener);
            // the subscribe-time snapshot delivers the current content synchronously
            assertEquals("cfg-value", notified.get());
        } finally {
            center.stop();
        }
    }
}
