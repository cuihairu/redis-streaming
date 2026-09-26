package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatStateManager;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cuihairu.redis.streaming.registry.heartbeat.UpdateDecision;

/**
 * Regression tests for B-05: when metrics collection yields nothing (collector failure,
 * timeout, everything disabled), the heartbeat must still be decided and executed. The
 * old code short-circuited empty metrics to NO_UPDATE, skipping executeUpdate entirely —
 * the instance's Redis TTL and heartbeat score stopped refreshing and the cleanup reaped
 * live instances precisely under load.
 */
class ProviderEmptyMetricsHeartbeatTest {

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static RedisServiceProvider newProvider(MetricsCollectionManager emptyCollector) throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(
                mock(RedissonClient.class),
                new ServiceProviderConfig(),
                new HeartbeatConfig(),
                emptyCollector);
        // isolate from global state / real Redis: control both collaborators
        set(provider, "stateManager", mock(HeartbeatStateManager.class));
        set(provider, "luaExecutor", mock(RegistryLuaScriptExecutor.class));
        return provider;
    }

    private static void processHeartbeat(RedisServiceProvider provider, ServiceInstance instance) throws Exception {
        Method m = RedisServiceProvider.class.getDeclaredMethod("processInstanceHeartbeat", ServiceInstance.class);
        m.setAccessible(true);
        m.invoke(provider, instance);
    }

    private static ServiceInstance instance() {
        return DefaultServiceInstance.builder()
                .serviceName("svc").instanceId("i1").host("127.0.0.1").port(8080)
                .ephemeral(true).build();
    }

    @Test
    void emptyMetricsStillExecutesHeartbeatOnlyUpdate() throws Exception {
        MetricsCollectionManager emptyCollector =
                new MetricsCollectionManager(Collections.emptyList(), new io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig());
        RedisServiceProvider provider = newProvider(emptyCollector);

        HeartbeatStateManager stateManager = get(provider, "stateManager");
        RegistryLuaScriptExecutor luaExecutor = get(provider, "luaExecutor");
        when(stateManager.shouldHeartbeatOnly("svc", "i1")).thenReturn(UpdateDecision.HEARTBEAT_ONLY);

        processHeartbeat(provider, instance());

        // the heartbeat decision was consulted (old code: never, NO_UPDATE short-circuit)
        verify(stateManager).shouldHeartbeatOnly("svc", "i1");
        // and the heartbeat actually reached Redis with heartbeat_only mode
        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        verify(luaExecutor).executeHeartbeatUpdate(any(), any(), eq("i1"), anyLong(), mode.capture(), isNull(), isNull(), anyInt());
        assertEquals("heartbeat_only", mode.getValue());
    }

    @Test
    void freshHeartbeatWithEmptyMetricsStaysNoUpdate() throws Exception {
        MetricsCollectionManager emptyCollector =
                new MetricsCollectionManager(Collections.emptyList(), new io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig());
        RedisServiceProvider provider = newProvider(emptyCollector);

        HeartbeatStateManager stateManager = get(provider, "stateManager");
        RegistryLuaScriptExecutor luaExecutor = get(provider, "luaExecutor");
        when(stateManager.shouldHeartbeatOnly("svc", "i1")).thenReturn(UpdateDecision.NO_UPDATE);

        processHeartbeat(provider, instance());

        verify(stateManager).shouldHeartbeatOnly("svc", "i1");
        verify(luaExecutor, never()).executeHeartbeatUpdate(any(), any(), any(), anyLong(), any(), any(), any(), anyInt());
    }
}
