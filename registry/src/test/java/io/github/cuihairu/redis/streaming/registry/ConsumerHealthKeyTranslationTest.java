package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.health.HealthCheckManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for B-04: HealthCheckManager keys (and reports) checkers by
 * {@code getUniqueId()} ("serviceName:instanceId") while the consumer's
 * discoveredInstances cache is keyed by the bare instanceId. The old direct lookup
 * always missed, so HEALTH_FAILURE/RECOVERY events never fired and the health
 * cache never updated; unregister/isInstanceHealthy never matched either.
 */
class ConsumerHealthKeyTranslationTest {

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
    void reportHealthStatusResolvesUniqueIdToBareInstanceId() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mock(RedissonClient.class));

        Map<String, ServiceInstance> cache = get(consumer, "discoveredInstances");
        cache.put("i1", DefaultServiceInstance.builder()
                .serviceName("svc").instanceId("i1").host("127.0.0.1").port(8080)
                .healthy(true).build());
        // seed the uniqueId -> instanceId translation the way discover() does
        Map<String, String> translation = get(consumer, "uniqueIdToInstanceId");
        translation.put("svc:i1", "i1");

        // route the reporter through the consumer exactly like initializeHealthCheckManager does
        HealthCheckManager manager = new HealthCheckManager(
                null,
                (key, isHealthy) -> invokeReport(consumer, key, isHealthy),
                1, TimeUnit.SECONDS);
        set(consumer, "healthCheckManager", manager);

        // simulate the checker reporting unhealthy under the uniqueId key
        invokeReport(consumer, "svc:i1", false);

        ServiceInstance updated = cache.get("i1");
        assertNotNull(updated, "instance must be found through the uniqueId translation");
        assertFalse(updated.isHealthy(),
                "health change must land on the cached instance (old code: silent no-op)");

        // an unknown id must not corrupt the cache nor throw
        invokeReport(consumer, "unknown", false);
        assertEquals(1, cache.size(), "unresolvable ids must be ignored");
    }

    @Test
    void isInstanceHealthyTranslatesBareIdToUniqueIdForCheckerLookup() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(mock(RedissonClient.class));

        Map<String, ServiceInstance> cache = get(consumer, "discoveredInstances");
        cache.put("i1", DefaultServiceInstance.builder()
                .serviceName("svc").instanceId("i1").host("127.0.0.1").port(8080).build());

        HealthCheckManager manager = mock(HealthCheckManager.class, Mockito.RETURNS_MOCKS);
        Mockito.when(manager.isInstanceHealthy("svc:i1")).thenReturn(true);
        set(consumer, "healthCheckManager", manager);

        assertTrue(consumer.isInstanceHealthy("i1"),
                "bare instanceId must be translated to the checker's uniqueId key");
        Mockito.verify(manager).isInstanceHealthy("svc:i1");
    }

    private static void invokeReport(RedisServiceConsumer consumer, String key, boolean healthy) {
        try {
            Method m = RedisServiceConsumer.class.getDeclaredMethod("reportHealthStatus", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(consumer, key, healthy);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
