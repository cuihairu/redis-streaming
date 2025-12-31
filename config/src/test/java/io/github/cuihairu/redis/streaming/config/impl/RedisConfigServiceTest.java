package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedisConfigService
 */
class RedisConfigServiceTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testConstructorWithConfig() {
        ConfigServiceConfig config = new ConfigServiceConfig();
        ConfigService service = new RedisConfigService(mockRedissonClient, config);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testConstructorWithNullConfig() {
        ConfigService service = new RedisConfigService(mockRedissonClient, null);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testStart() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertFalse(service.isRunning());
        service.start();
        assertTrue(service.isRunning());
    }

    @Test
    void testStop() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testStartIsIdempotent() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        service.start();
        assertTrue(service.isRunning());

        service.start(); // Should not cause issues
        assertTrue(service.isRunning());
    }

    @Test
    void testStopIsIdempotent() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        service.stop();
        assertFalse(service.isRunning());

        service.stop(); // Should not cause issues
        assertFalse(service.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertDoesNotThrow(() -> service.stop());
        assertFalse(service.isRunning());
    }

    @Test
    void testGetConfigWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> service.getConfig("test-data-id", "test-group"));
    }

    @Test
    void testPublishConfigWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.publishConfig("test-data-id", "test-group", "test-content")
        );
    }

    @Test
    void testPublishConfigWithDescriptionWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.publishConfig("test-data-id", "test-group", "test-content", "test-description")
        );
    }

    @Test
    void testRemoveConfigWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.removeConfig("test-data-id", "test-group")
        );
    }

    @Test
    void testAddListenerWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        ConfigChangeListener listener = (dataId, group, content, version) -> {};
        assertThrows(IllegalStateException.class, () ->
                service.addListener("test-data-id", "test-group", listener)
        );
    }

    @Test
    void testRemoveListenerWhenNotRunning() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        ConfigChangeListener listener = (dataId, group, content, version) -> {};
        assertDoesNotThrow(() ->
                service.removeListener("test-data-id", "test-group", listener)
        );
    }

    @Test
    void testGetConfigHistoryWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.getConfigHistory("test-data-id", "test-group", 10)
        );
    }

    @Test
    void testTrimHistoryBySizeWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.trimHistoryBySize("test-data-id", "test-group", 100)
        );
    }

    @Test
    void testTrimHistoryByAgeWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.trimHistoryByAge("test-data-id", "test-group", Duration.ofHours(1))
        );
    }

    @Test
    void testLifecycleTransitions() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        // Initial state
        assertFalse(service.isRunning());

        // Start
        service.start();
        assertTrue(service.isRunning());

        // Stop
        service.stop();
        assertFalse(service.isRunning());

        // Restart
        service.start();
        assertTrue(service.isRunning());

        // Final stop
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testGetConfigHistoryWithZeroSize() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<?> history = service.getConfigHistory("test-data-id", "test-group", 0);

        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    void testGetConfigHistoryWithNegativeSize() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<?> history = service.getConfigHistory("test-data-id", "test-group", -10);

        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    void testGetConfigHistoryWithLargeSize() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<?> history = service.getConfigHistory("test-data-id", "test-group", 10000);

        assertNotNull(history);
    }

    @Test
    void testTrimHistoryBySizeWithNegativeMax() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryBySize("test-data-id", "test-group", -1);

        // Negative size should delete all history
        assertTrue(removed >= 0);
    }

    @Test
    void testTrimHistoryBySizeWithZeroMax() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryBySize("test-data-id", "test-group", 0);

        assertTrue(removed >= 0);
    }

    @Test
    void testTrimHistoryByAgeWithNullDuration() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryByAge("test-data-id", "test-group", null);

        assertTrue(removed >= 0);
    }

    @Test
    void testTrimHistoryByAgeWithZeroDuration() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryByAge("test-data-id", "test-group", Duration.ZERO);

        assertTrue(removed >= 0);
    }

    @Test
    void testRemoveListenerNonExistent() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        ConfigChangeListener listener = (dataId, group, content, version) -> {};
        assertDoesNotThrow(() ->
                service.removeListener("test-data-id", "test-group", listener)
        );
    }

    @Test
    void testGetConfigReadsContentFromRedis() {
        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RMap) map);
        when(map.get("content")).thenReturn("v1");

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertEquals("v1", service.getConfig("d1", "g1"));
        verify(map).get("content");
    }

    @Test
    void testPublishConfigLuaSuccessPublishesChangeEvent() {
        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doReturn(0L).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(),
                any(), any(), any(), any(), any());

        RTopic topic = mock(RTopic.class);
        when(mockRedissonClient.getTopic(anyString(), any())).thenReturn(topic);
        when(topic.publish(any())).thenReturn(1L);

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertTrue(service.publishConfig("d1", "g1", "content", "desc"));

        ArgumentCaptor<ConfigChangeEvent> captor = ArgumentCaptor.forClass(ConfigChangeEvent.class);
        verify(topic, atLeastOnce()).publish(captor.capture());
        assertEquals("d1", captor.getValue().getDataId());
        assertEquals("g1", captor.getValue().getGroup());
        assertEquals("content", captor.getValue().getContent());
        assertNotNull(captor.getValue().getVersion());
    }

    @Test
    void testPublishConfigLuaFailureFallsBackToJavaWritePath() {
        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doThrow(new RuntimeException("lua fail")).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(),
                any(), any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RMap) map);
        when(map.readAllMap()).thenReturn(java.util.Collections.emptyMap());
        when(map.fastPut(anyString(), anyString())).thenReturn(true);
        when(map.fastRemove(anyString())).thenReturn(1L);

        RTopic topic = mock(RTopic.class);
        when(mockRedissonClient.getTopic(anyString(), any())).thenReturn(topic);
        when(topic.publish(any())).thenReturn(1L);

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertTrue(service.publishConfig("d1", "g1", "content", ""));

        verify(map, atLeastOnce()).fastPut(eq("content"), eq("content"));
        verify(map, atLeastOnce()).fastPut(eq("version"), anyString());
        verify(map, atLeastOnce()).fastPut(eq("updateTime"), anyString());
        verify(map, atLeastOnce()).fastPut(eq("createTime"), anyString());
        verify(map, atLeastOnce()).fastRemove(eq("description"));
    }

    @Test
    void testRemoveConfigLuaDeletedPublishesDeleteEventAndClearsSubscribers() {
        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doReturn(1L).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(),
                any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        RSet<String> subs = mock(RSet.class);
        when(mockRedissonClient.getSet(anyString())).thenReturn((RSet) subs);
        when(subs.delete()).thenReturn(true);

        RTopic topic = mock(RTopic.class);
        when(mockRedissonClient.getTopic(anyString(), any())).thenReturn(topic);
        when(topic.publish(any())).thenReturn(1L);

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertTrue(service.removeConfig("d1", "g1"));
        verify(subs).delete();

        ArgumentCaptor<ConfigChangeEvent> captor = ArgumentCaptor.forClass(ConfigChangeEvent.class);
        verify(topic, atLeastOnce()).publish(captor.capture());
        assertEquals("d1", captor.getValue().getDataId());
        assertEquals("g1", captor.getValue().getGroup());
        assertNull(captor.getValue().getContent());
        assertNull(captor.getValue().getVersion());
    }

    @Test
    void testRemoveConfigLuaNotDeletedDoesNotPublishEventButStillClearsSubscribers() {
        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doReturn(0L).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(),
                any(), any(), any(), any());

        @SuppressWarnings("unchecked")
        RSet<String> subs = mock(RSet.class);
        when(mockRedissonClient.getSet(anyString())).thenReturn((RSet) subs);
        when(subs.delete()).thenReturn(true);

        RTopic topic = mock(RTopic.class);
        when(mockRedissonClient.getTopic(anyString(), any())).thenReturn(topic);
        when(topic.publish(any())).thenReturn(1L);

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertFalse(service.removeConfig("d1", "g1"));
        verify(subs).delete();
        verify(topic, never()).publish(any());
    }

    @Test
    void testAddListenerNotifiesCurrentConfigAndRegistersSubscriber() {
        RTopic topic = mock(RTopic.class);
        when(mockRedissonClient.getTopic(anyString(), any())).thenReturn(topic);
        when(topic.addListener(eq(ConfigChangeEvent.class), any())).thenReturn(1);

        @SuppressWarnings("unchecked")
        RSet<String> subs = mock(RSet.class);
        when(mockRedissonClient.getSet(anyString())).thenReturn((RSet) subs);
        when(subs.add(anyString())).thenReturn(true);

        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RMap) map);
        Map<String, String> all = new HashMap<>();
        all.put("content", "c1");
        all.put("version", "v1");
        all.put("updateTime", String.valueOf(System.currentTimeMillis()));
        all.put("createTime", String.valueOf(System.currentTimeMillis()));
        when(map.readAllMap()).thenReturn(all);

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        class Recorder implements ConfigChangeListener {
            String content;
            String version;

            @Override
            public void onConfigChange(String dataId, String group, String content, String version) {
                this.content = content;
                this.version = version;
            }
        }

        Recorder listener = new Recorder();
        service.addListener("d1", "g1", listener);

        assertEquals("c1", listener.content);
        assertEquals("v1", listener.version);
        verify(subs).add(anyString());
    }

    @Test
    void testRemoveListenerCleansUpSubscriptionWhenLastListenerRemoved() {
        RTopic topic = mock(RTopic.class);
        when(mockRedissonClient.getTopic(anyString(), any())).thenReturn(topic);
        when(topic.addListener(eq(ConfigChangeEvent.class), any())).thenReturn(1);

        @SuppressWarnings("unchecked")
        RSet<String> subs = mock(RSet.class);
        when(mockRedissonClient.getSet(anyString())).thenReturn((RSet) subs);
        when(subs.add(anyString())).thenReturn(true);
        when(subs.remove(anyString())).thenReturn(true);

        @SuppressWarnings("unchecked")
        RMap<String, String> map = mock(RMap.class);
        when(mockRedissonClient.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RMap) map);
        when(map.readAllMap()).thenReturn(java.util.Collections.emptyMap());

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        ConfigChangeListener listener = (d, g, c, v) -> {};
        service.addListener("d1", "g1", listener);
        service.removeListener("d1", "g1", listener);

        verify(topic).removeAllListeners();
        verify(subs).remove(anyString());
    }

    @Test
    void testGetConfigHistoryParsesJsonRecords() {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(mockRedissonClient.getList(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RList) list);
        when(list.size()).thenReturn(1);
        String json = "{\"dataId\":\"d1\",\"group\":\"g1\",\"content\":\"c1\",\"version\":\"v1\",\"operation\":\"UPDATED\",\"changeTime\":1700000000000,\"operator\":\"system\"}";
        when(list.range(0, 0)).thenReturn(java.util.Collections.singletonList(json));

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<ConfigHistory> history = service.getConfigHistory("d1", "g1", 10);
        assertEquals(1, history.size());
        assertEquals("d1", history.get(0).getDataId());
        assertEquals("g1", history.get(0).getGroup());
        assertEquals("c1", history.get(0).getContent());
        assertEquals("v1", history.get(0).getVersion());
        assertEquals("UPDATED", history.get(0).getDescription());
        assertEquals("system", history.get(0).getOperator());
        assertNotNull(history.get(0).getChangeTime());
    }

    @Test
    void testTrimHistoryBySizeUsesLua() {
        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doReturn(3L).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(), any());

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertEquals(3, service.trimHistoryBySize("d1", "g1", 10));
    }

    @Test
    void testTrimHistoryByAgeUsesLua() {
        RScript script = mock(RScript.class);
        when(mockRedissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE)).thenReturn(script);
        doReturn(2L).when(script).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER), anyList(), any());

        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertEquals(2, service.trimHistoryByAge("d1", "g1", Duration.ofSeconds(1)));
    }
}
