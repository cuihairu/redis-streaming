package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-06 unit coverage: the reconciliation poll re-delivers config changes whose pub/sub
 * notification never reached this JVM. Compares content against the last delivered
 * state, adopts silently when no state was ever observed, treats absence as removal,
 * and never propagates Redis errors.
 */
class ConfigResyncReconciliationTest {

    private static final class Recording {
        final List<String> contents = new CopyOnWriteArrayList<>();
        final List<String> versions = new CopyOnWriteArrayList<>();
        final ConfigChangeListener listener = (d, g, c, v) -> {
            contents.add(c);
            versions.add(v);
        };
    }

    @SuppressWarnings("unchecked")
    private static RMap<String, String> stubConfigMap(RedissonClient redisson, AtomicReference<Map<String, String>> state) {
        RMap<String, String> configMap = mock(RMap.class);
        when(configMap.readAllMap()).thenAnswer(inv -> new HashMap<>(state.get()));
        when(redisson.getMap(anyString(), any(StringCodec.class))).thenReturn((RMap) configMap);
        return configMap;
    }

    @SuppressWarnings("unchecked")
    private static RedissonClient mockRedisson(AtomicReference<Map<String, String>> state) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<ConfigChangeEvent>getTopic(anyString(), any())).thenReturn(mock(RTopic.class));
        when(redisson.<String>getSet(anyString())).thenReturn(mock(RSet.class));
        stubConfigMap(redisson, state);
        return redisson;
    }

    private static Map<String, String> entry(String content, String version) {
        Map<String, String> m = new HashMap<>();
        if (content != null) m.put("content", content);
        if (version != null) m.put("version", version);
        m.put("updateTime", "1");
        return m;
    }

    @Test
    void resyncDeliversMissedChangeFromRedis() throws Exception {
        AtomicReference<Map<String, String>> state = new AtomicReference<>(new HashMap<>());
        RedissonClient redisson = mockRedisson(state);
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig());
        service.start();
        try {
            Recording rec = new Recording();
            service.addListener("d", "g", rec.listener);   // absent config: no delivery, baseline = absent

            state.set(entry("v2", "55-1"));                // the publish this JVM never heard about
            service.resyncSubscribedConfigs();

            assertEquals(List.of("v2"), rec.contents, "missed change must be re-delivered from Redis");
            assertEquals(List.of("55-1"), rec.versions);

            service.resyncSubscribedConfigs();             // baseline now matches: no repeat
            assertEquals(List.of("v2"), rec.contents, "a reconciled state must not be delivered twice");
        } finally {
            service.stop();
        }
    }

    @Test
    void resyncSkipsWhenContentMatchesTheLastDeliveredState() throws Exception {
        AtomicReference<Map<String, String>> state = new AtomicReference<>(entry("v1", "1-0"));
        RedissonClient redisson = mockRedisson(state);
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig());
        service.start();
        try {
            Recording rec = new Recording();
            service.addListener("d", "g", rec.listener);   // initial delivery: v1
            assertEquals(List.of("v1"), rec.contents);

            service.resyncSubscribedConfigs();
            assertEquals(List.of("v1"), rec.contents, "no spurious delivery when Redis matches the baseline");
        } finally {
            service.stop();
        }
    }

    @Test
    void resyncDeliversRemovalAsNullContent() throws Exception {
        AtomicReference<Map<String, String>> state = new AtomicReference<>(entry("v1", "1-0"));
        RedissonClient redisson = mockRedisson(state);
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig());
        service.start();
        try {
            Recording rec = new Recording();
            service.addListener("d", "g", rec.listener);   // initial delivery: v1
            state.set(new HashMap<>());                    // the config was deleted without this JVM seeing it
            service.resyncSubscribedConfigs();

            assertEquals(java.util.Arrays.asList("v1", null), rec.contents, "a missed removal must arrive as null content");
        } finally {
            service.stop();
        }
    }

    @Test
    void resyncAdoptsBaselineSilentlyWhenNoStateWasEverObserved() throws Exception {
        AtomicReference<Map<String, String>> state = new AtomicReference<>(entry("v1", "1-0"));
        RedissonClient redisson = mockRedisson(state);
        RMap<String, String> configMap = mock(RMap.class);
        when(redisson.getMap(anyString(), any(StringCodec.class))).thenReturn((RMap) configMap);
        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig());
        service.start();
        try {
            // first read (addListener's snapshot) fails -> baseline stays UNSET
            when(configMap.readAllMap()).thenThrow(new RuntimeException("redis down"))
                    .thenAnswer(inv -> new HashMap<>(state.get()));

            Recording rec = new Recording();
            service.addListener("d", "g", rec.listener);
            assertEquals(List.of(), rec.contents);

            state.set(entry("v2", "2-0"));
            service.resyncSubscribedConfigs();
            assertEquals(List.of(), rec.contents,
                    "adopting the first observable state must stay silent (subscribe-time snapshot semantics)");

            state.set(entry("v3", "3-0"));
            service.resyncSubscribedConfigs();
            assertEquals(List.of("v3"), rec.contents, "deviations after the silent adoption must be delivered");
        } finally {
            service.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resyncSwallowsRedisErrorsAndSchedulerFollowsConfig() throws Exception {
        AtomicReference<Map<String, String>> state = new AtomicReference<>(new HashMap<>());
        RedissonClient redisson = mockRedisson(state);
        RMap<String, String> configMap = mock(RMap.class);
        when(redisson.getMap(anyString(), any(StringCodec.class))).thenReturn((RMap) configMap);
        when(configMap.readAllMap()).thenThrow(new RuntimeException("redis down"));

        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig());
        service.start();
        Field schedulerField = RedisConfigService.class.getDeclaredField("resyncScheduler");
        schedulerField.setAccessible(true);
        assertNotNull(schedulerField.get(service), "default interval schedules the resync poll");
        try {
            Recording rec = new Recording();
            service.addListener("d", "g", rec.listener);
            assertDoesNotThrow(service::resyncSubscribedConfigs, "a Redis error must not propagate out of the poll");
        } finally {
            service.stop();
        }
        assertNull(schedulerField.get(service), "stop() must shut the poll down");

        ConfigServiceConfig off = new ConfigServiceConfig();
        off.setResyncIntervalMs(0);
        RedisConfigService idle = new RedisConfigService(redisson, off);
        idle.start();
        try {
            assertNull(schedulerField.get(idle), "interval 0 must disable the poll entirely");
        } finally {
            idle.stop();
        }
    }
}
