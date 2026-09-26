package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for RedisConfigService paths that unit tests with mocked Redisson can reach:
 * Lua success, Java fallback, hard failure, listener lifecycle and history building.
 */
class RedisConfigServiceBranchCoverageTest {

    private RedissonClient redisson;
    private RScript script;
    private RMap<String, String> configMap;
    private RList<String> historyList;
    private RSet<String> subscribers;
    private RTopic topic;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        configMap = mock(RMap.class);
        historyList = mock(RList.class);
        subscribers = mock(RSet.class);
        topic = mock(RTopic.class);

        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(configMap);
        when(redisson.<String>getList(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(historyList);
        when(redisson.<String>getSet(anyString())).thenReturn(subscribers);
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(topic);
    }

    private RedisConfigService startedService() {
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig("ut-cfg", true));
        service.start();
        return service;
    }

    @Test
    void getConfigReturnsContentOnHappyPath() {
        when(configMap.get("content")).thenReturn("hello");
        RedisConfigService service = startedService();
        assertEquals("hello", service.getConfig("d1", "g1"));
    }

    @Test
    void getConfigWrapsBackendFailure() {
        when(configMap.get("content")).thenThrow(new IllegalStateException("redis down"));
        RedisConfigService service = startedService();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.getConfig("d1", "g1"));
        assertTrue(ex.getMessage().contains("Failed to get config"));
    }

    @Test
    void publishConfigLuaPathSucceedsAndNotifiesLocalListener() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);
        RedisConfigService service = startedService();
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        service.addListener("d1", "g1", (id, g, content, version) -> seen.add(String.valueOf(content)));

        assertTrue(service.publishConfig("d1", "g1", "v1", "first"));
        assertTrue(service.publishConfig("d1", "g1", null));
        assertEquals(2, seen.size(), "local delivery happens synchronously: " + seen);
    }

    @Test
    void publishConfigFallsBackToJavaWhenLuaFails() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(Map.of("content", "old", "version", "v0"));
        when(historyList.size()).thenReturn(0);

        RedisConfigService service = startedService();
        assertTrue(service.publishConfig("d1", "g1", "new", "desc"));
        verify(historyList).add(eq(0), anyString());
        verify(configMap).fastPut("content", "new");
        verify(configMap).fastPut("description", "desc");
    }

    @Test
    void publishConfigFallbackToleratesMissingContentAndDescription() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(new HashMap<>());

        RedisConfigService service = startedService();
        assertTrue(service.publishConfig("d1", "g1", null, null));
        verify(configMap).fastRemove("content");
        verify(configMap).fastRemove("description");
    }

    @Test
    void publishConfigReturnsFalseWhenLuaAndFallbackBothFail() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenThrow(new IllegalStateException("redis gone"));
        when(configMap.fastPut(anyString(), anyString())).thenThrow(new IllegalStateException("redis gone"));

        RedisConfigService service = startedService();
        assertFalse(service.publishConfig("d1", "g1", "v"));
    }

    @Test
    void removeConfigLuaPathReturnsDeletedFlag() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);
        RedisConfigService service = startedService();
        assertTrue(service.removeConfig("d1", "g1"));
        verify(subscribers).delete();
    }

    @Test
    void removeConfigReturnsFalseWhenNothingDeleted() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(0L);
        RedisConfigService service = startedService();
        assertFalse(service.removeConfig("d1", "g1"));
    }

    @Test
    void removeConfigFallbackSavesHistoryThenDeletes() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(Map.of("content", "old", "version", "v0"));
        when(historyList.size()).thenReturn(0);

        RedisConfigService service = startedService();
        assertTrue(service.removeConfig("d1", "g1"));
        verify(historyList).add(eq(0), anyString());
        verify(configMap).delete();
    }

    @Test
    void removeConfigFallbackWithoutOldContentSkipsHistory() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(new HashMap<>());

        RedisConfigService service = startedService();
        assertTrue(service.removeConfig("d1", "g1"));
        verify(historyList, never()).add(anyInt(), anyString());
    }

    @Test
    void removeConfigFallbackSurvivesBackendFailures() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenThrow(new IllegalStateException("redis gone"));
        when(configMap.delete()).thenThrow(new IllegalStateException("redis gone"));

        RedisConfigService service = startedService();
        // per-step failures inside the fallback are swallowed best-effort; removal still reports success
        assertTrue(service.removeConfig("d1", "g1"));
    }

    @Test
    void addListenerImmediatelyNotifiesWithCurrentConfig() {
        when(configMap.readAllMap()).thenReturn(Map.of("content", "current", "version", "v9"));
        RedisConfigService service = startedService();

        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        service.addListener("d1", "g1", (id, g, content, version) -> seen.add(content + "@" + version));
        assertEquals(List.of("current@v9"), seen);

        // second listener reuses the existing subscription
        service.addListener("d1", "g1", (id, g, content, version) -> { });
        verify(topic, org.mockito.Mockito.times(1)).addListener(any(), any());
    }

    @Test
    void addListenerToleratesReadFailure() {
        when(configMap.readAllMap()).thenThrow(new IllegalStateException("redis gone"));
        RedisConfigService service = startedService();
        assertDoesNotThrow(() -> service.addListener("d1", "g1", (id, g, content, version) -> { }));
    }

    @Test
    void removeLastListenerCancelsSubscription() {
        RedisConfigService service = startedService();
        ConfigChangeListener listener = (id, g, content, version) -> { };
        service.addListener("d1", "g1", listener);
        service.removeListener("d1", "g1", listener);
        verify(topic).removeAllListeners();
        verify(subscribers).remove(anyString());
    }

    @Test
    void removeListenerWithoutRegistrationIsTolerated() {
        RedisConfigService service = startedService();
        assertDoesNotThrow(() -> service.removeListener("never", "g1", (id, g, c, v) -> { }));
    }

    @Test
    void stopCleansUpActiveSubscriptions() {
        RedisConfigService service = startedService();
        service.addListener("d1", "g1", (id, g, content, version) -> { });
        service.stop();
        verify(topic).removeAllListeners();
        assertFalse(service.isRunning());
    }

    @Test
    void trimHistoryBySizeAndAgeReturnScriptResults() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(3L)
                .thenReturn(null);
        RedisConfigService service = startedService();
        assertEquals(3, service.trimHistoryBySize("d1", "g1", 5));
        assertEquals(0, service.trimHistoryBySize("d1", "g1", 5), "null script result maps to 0");
        assertEquals(0, service.trimHistoryByAge("d1", "g1", Duration.ofHours(1)), "null script result maps to 0");
    }

    @Test
    void trimHistorySwallowsBackendFailures() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        RedisConfigService service = startedService();
        assertEquals(0, service.trimHistoryBySize("d1", "g1", 5));
        assertEquals(0, service.trimHistoryByAge("d1", "g1", null));
    }

    @Test
    void getConfigHistoryBuildsEntriesAndToleratesBadJson() {
        when(historyList.size()).thenReturn(3);
        when(historyList.range(0, 2)).thenReturn(List.of(
                "{\"dataId\":\"d1\",\"group\":\"g1\",\"content\":\"a\",\"version\":\"v1\",\"operation\":\"UPDATED\",\"changeTime\":1700000000000,\"operator\":\"system\"}",
                "not-json",
                "{\"dataId\":\"d1\",\"group\":\"g1\"}"));

        RedisConfigService service = startedService();
        List<ConfigHistory> history = service.getConfigHistory("d1", "g1", 10);
        assertEquals(2, history.size(), "malformed entry is filtered out");
        assertEquals("a", history.get(0).getContent());
        assertNotNull(history.get(0).getChangeTime());
        assertNotNull(history.get(1));
    }

    @Test
    void getConfigHistoryErrorsReturnEmptyList() {
        when(historyList.size()).thenThrow(new IllegalStateException("redis gone"));
        RedisConfigService service = startedService();
        assertTrue(service.getConfigHistory("d1", "g1", 5).isEmpty());
    }

    @Test
    void buildConfigHistoryAcceptsMapInputViaReflection() throws Exception {
        RedisConfigService service = startedService();
        java.lang.reflect.Method m = RedisConfigService.class
                .getDeclaredMethod("buildConfigHistory", Object.class);
        m.setAccessible(true);

        Map<String, Object> record = new HashMap<>();
        record.put("dataId", "d");
        record.put("group", "g");
        record.put("content", "c");
        record.put("version", null);
        record.put("changeTime", 1700000000000L);
        ConfigHistory history = (ConfigHistory) m.invoke(service, record);
        assertNotNull(history);
        assertEquals("c", history.getContent());
        assertNull(history.getVersion());

        Map<String, Object> minimal = new HashMap<>();
        minimal.put("changeTime", "not-a-long");
        ConfigHistory built = (ConfigHistory) m.invoke(service, minimal);
        assertNotNull(built);
    }

    @Test
    void handleConfigChangeEventSwallowsListenerFailures() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);
        RedisConfigService service = startedService();
        CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
        service.addListener("d1", "g1", (id, g, content, version) -> {
            throw new IllegalStateException("listener bug");
        });
        service.addListener("d1", "g1", (id, g, content, version) -> seen.add(content));

        assertTrue(service.publishConfig("d1", "g1", "v1"));
        assertEquals(1, seen.size(), "failing listener must not block the rest");
    }

    @Test
    void notRunningOperationsThrow() {
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig("ut-cfg", true));
        assertThrows(IllegalStateException.class, () -> service.getConfig("d", "g"));
        assertThrows(IllegalStateException.class, () -> service.publishConfig("d", "g", "v"));
        assertThrows(IllegalStateException.class, () -> service.removeConfig("d", "g"));
        assertThrows(IllegalStateException.class, () -> service.addListener("d", "g", (i, gr, c, v) -> { }));
        assertThrows(IllegalStateException.class, () -> service.getConfigHistory("d", "g", 1));
        assertThrows(IllegalStateException.class, () -> service.trimHistoryBySize("d", "g", 1));
        assertThrows(IllegalStateException.class, () -> service.trimHistoryByAge("d", "g", Duration.ofDays(1)));
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
