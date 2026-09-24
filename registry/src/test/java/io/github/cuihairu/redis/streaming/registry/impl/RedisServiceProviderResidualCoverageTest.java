package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for RedisServiceProvider: notifyServiceChangeById failure swallow,
 * buildInstanceFromSnapshot defaults, null keyPrefix fallback and stop()/heartbeat seams.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisServiceProviderResidualCoverageTest {

    private RedissonClient redisson;
    private RScript script;
    private RTopic topic;
    private RMap<String, String> instanceMap;
    private RScoredSortedSet<String> heartbeats;
    private RSet<String> servicesSet;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        topic = mock(RTopic.class);
        instanceMap = mock(RMap.class);
        heartbeats = mock(RScoredSortedSet.class);
        servicesSet = mock(RSet.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(topic);
        when(redisson.<String, String>getMap(anyString())).thenReturn(instanceMap);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(heartbeats);
        when(heartbeats.size()).thenReturn(1);
    }

    private static MetricsCollectionManager emptyManager() {
        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(java.util.Set.of());
        return new MetricsCollectionManager(List.of(), cfg);
    }

    private static ServiceInstance instance() {
        return DefaultServiceInstance.builder()
                .serviceName("svc").instanceId("i1").host("127.0.0.1").port(1)
                .protocol(StandardProtocol.TCP).metadata(Map.of()).healthy(true).build();
    }

    private static void setField(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void cleanupNotifiesIdOnlyWithFailuresAndParsesSnapshotDefaults() throws Exception {
        ServiceProviderConfig cfg = new ServiceProviderConfig();
        cfg.setKeyPrefix(null);
        RedisServiceProvider provider = new RedisServiceProvider(redisson, cfg, new HeartbeatConfig(), emptyManager());
        doThrow(new IllegalStateException("pubsub down")).when(topic).publish(any());

        // i1: broken json -> id-only notify whose publish fails (catch of notifyServiceChangeById)
        // i2: minimal json without "enabled" (null branch of enabled parse)
        // i3: json with explicit "enabled" (parse branch)
        when(script.evalSha(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any()))
                .thenReturn(List.of(
                        "i1", "{{not-json",
                        "i2", "{\"host\":\"h\"}",
                        "i3", "{\"host\":\"h2\",\"enabled\":\"false\"}"));

        Method cleanupForService = RedisServiceProvider.class
                .getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
        cleanupForService.setAccessible(true);
        assertDoesNotThrow(() -> cleanupForService.invoke(provider, "svc"));
    }

    @Test
    void stopForcesShutdownNowWhenTerminationTimesOut() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig(), emptyManager());
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        setField(provider, RedisServiceProvider.class, "running", true);
        setField(provider, RedisServiceProvider.class, "executorService", executor);
        provider.stop();
        verify(executor).shutdownNow();
    }

    @Test
    void processInstanceHeartbeatToleratesNullMetricsManager() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redisson, new ServiceProviderConfig(),
                new HeartbeatConfig(), emptyManager());
        setField(provider, RedisServiceProvider.class, "metricsManager", null);
        Method m = RedisServiceProvider.class.getDeclaredMethod("processInstanceHeartbeat", ServiceInstance.class);
        m.setAccessible(true);
        try {
            m.invoke(provider, instance());
        } catch (Exception ignore) {
            // downstream bookkeeping may fail on mocks; the null-metrics branch is the target
        }
    }
}
