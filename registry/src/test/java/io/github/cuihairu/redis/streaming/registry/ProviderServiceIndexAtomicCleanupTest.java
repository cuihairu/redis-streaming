package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatStateManager;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for B-29: after cleaning expired heartbeats the provider dropped the
 * service from the services index with a plain {@code if (set.size() == 0) SREM}
 * check-then-act. An instance registering between the size check and the SREM got its
 * service evicted from the index while its heartbeat ZSet was non-empty — the cleanup
 * sweep never revisited the service again, leaving a permanent un-TTL'd orphan ZSet.
 *
 * <p>The check and the removal must run as one atomic server-side step (Lua ZCARD/SREM).
 */
class ProviderServiceIndexAtomicCleanupTest {

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

    @Test
    void emptyHeartbeatSetDropsServiceIndexEntryAtomically() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        ServiceProviderConfig config = new ServiceProviderConfig();
        RedisServiceProvider provider = new RedisServiceProvider(
                redisson, config, new HeartbeatConfig(),
                new MetricsCollectionManager(Collections.emptyList(), new MetricsConfig()));
        set(provider, "stateManager", mock(HeartbeatStateManager.class));
        RegistryLuaScriptExecutor luaExecutor = mock(RegistryLuaScriptExecutor.class);
        set(provider, "luaExecutor", luaExecutor);
        when(luaExecutor.executeCleanupExpiredInstancesWithSnapshots(anyString(), anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(Collections.emptyList());

        RScript script = mock(RScript.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);

        invokeCleanupForService(provider, "svc-x");

        ArgumentCaptor<String> lua = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> keys = ArgumentCaptor.forClass((Class) List.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), lua.capture(), eq(RScript.ReturnType.LONG), keys.capture(), eq("svc-x"));

        assertTrue(lua.getValue().contains("ZCARD"), "emptiness check must be server-side");
        assertTrue(lua.getValue().contains("SREM"), "index removal must be in the same atomic step");

        List<Object> usedKeys = keys.getValue();
        assertEquals(2, usedKeys.size());
        assertTrue(usedKeys.contains(config.getRegistryKeys().getServiceHeartbeatsKey("svc-x")));
        assertTrue(usedKeys.contains(config.getRegistryKeys().getServicesIndexKey()));
    }

    private static void invokeCleanupForService(RedisServiceProvider provider, String serviceName) throws Exception {
        Method m = RedisServiceProvider.class.getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
        m.setAccessible(true);
        m.invoke(provider, serviceName);
    }
}
