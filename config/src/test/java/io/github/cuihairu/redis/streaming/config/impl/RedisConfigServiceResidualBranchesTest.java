package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Residual branch coverage for RedisConfigService fallback/parse/trim/cleanup edges. */
class RedisConfigServiceResidualBranchesTest {

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
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig("res-cfg", true));
        service.start();
        return service;
    }

    @Test
    void fallbackPublishParsesStoredUpdateTime() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        Map<String, String> entry = new HashMap<>();
        entry.put("content", "old");
        entry.put("version", "v0");
        entry.put("updateTime", "1700000000000");
        when(configMap.readAllMap()).thenReturn(entry);
        when(historyList.size()).thenReturn(0);

        RedisConfigService service = startedService();
        assertTrue(service.publishConfig("d", "g", "new"));

        // unparsable updateTime also tolerated
        entry.put("updateTime", "not-a-long");
        assertTrue(service.publishConfig("d", "g", "newer"));
    }

    @Test
    void fallbackPublishSurvivesFastRemoveFailures() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(Map.of("content", "old"));
        doThrow(new IllegalStateException("boom")).when(configMap).fastRemove(anyString());

        RedisConfigService service = startedService();
        assertTrue(service.publishConfig("d", "g", null, null));
    }

    @Test
    void saveConfigHistoryTrimsOverflow() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(Map.of("content", "old"));
        when(historyList.size()).thenReturn(100); // above maxHistorySize -> trim path

        RedisConfigService service = startedService();
        assertTrue(service.publishConfig("d", "g", "new"));
    }

    @Test
    void removeFallbackSurvivesSubscriberDeleteFailure() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        when(configMap.readAllMap()).thenReturn(Map.of("content", "old"));
        when(historyList.size()).thenReturn(0);
        doThrow(new IllegalStateException("boom")).when(subscribers).delete();

        RedisConfigService service = startedService();
        assertTrue(service.removeConfig("d", "g"));
    }

    @Test
    void removeListenerSurvivesSubscriberRemoveFailure() {
        doThrow(new IllegalStateException("boom")).when(subscribers).remove(anyString());
        RedisConfigService service = startedService();
        ConfigChangeListener listener = (id, g, c, v) -> { };
        service.addListener("d", "g", listener);
        assertDoesNotThrow(() -> service.removeListener("d", "g", listener));
    }

    @Test
    void stopSurvivesRemoveAllListenersFailure() {
        doThrow(new IllegalStateException("boom")).when(topic).removeAllListeners();
        RedisConfigService service = startedService();
        service.addListener("d", "g", (id, g, c, v) -> { });
        assertDoesNotThrow(service::stop);
    }

    @Test
    void handleConfigChangeEventWithNullMessageHitsOuterCatch() throws Exception {
        RedisConfigService service = startedService();
        java.lang.reflect.Method m = RedisConfigService.class.getDeclaredMethod(
                "handleConfigChangeEvent", String.class, String.class, ConfigChangeEvent.class);
        m.setAccessible(true);
        assertDoesNotThrow(() -> m.invoke(service, new Object[]{"d", "g", null}));
    }

    @Test
    void removeFallbackHitsHardFailureWhenMapReadThrowsUnguarded() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("NOSCRIPT"));
        @SuppressWarnings("unchecked")
        Map<String, String> evil = mock(Map.class);
        when(evil.get("content")).thenThrow(new IllegalStateException("map gone"));
        when(configMap.readAllMap()).thenReturn(evil);

        RedisConfigService service = startedService();
        assertFalse(service.removeConfig("d", "g"), "hard failure inside fallback reports false");
    }

    @Test
    void publishConfigChangeEventCoversGetTopicFailure() {
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenThrow(new IllegalStateException("topic unavailable"));

        RedisConfigService service = startedService();
        assertTrue(service.publishConfig("d", "g", "v"), "publish succeeds even when event bus is down");
    }

    @Test
    void generateVersionResetsSequenceAtOverflow() throws Exception {
        java.lang.reflect.Field seqField = RedisConfigService.class.getDeclaredField("SEQ");
        seqField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicInteger seq =
                (java.util.concurrent.atomic.AtomicInteger) seqField.get(null);
        int original = seq.get();
        try {
            RedisConfigService service = startedService();
            java.lang.reflect.Method gen = RedisConfigService.class.getDeclaredMethod("generateVersion");
            gen.setAccessible(true);
            // force the same-millisecond path with overflow counter
            java.lang.reflect.Field lastField = RedisConfigService.class.getDeclaredField("LAST_TS");
            lastField.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong last =
                    (java.util.concurrent.atomic.AtomicLong) lastField.get(null);
            long now = System.currentTimeMillis();
            last.set(now);
            seq.set(9999);
            String v = (String) gen.invoke(service);
            assertTrue(v.endsWith("-0"), "counter resets past 9999: " + v);
            last.set(System.currentTimeMillis());
            String v2 = (String) gen.invoke(service);
            assertFalse(v2.isEmpty());
        } finally {
            seq.set(original);
        }
    }
}
