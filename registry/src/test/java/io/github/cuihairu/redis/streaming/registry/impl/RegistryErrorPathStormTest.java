package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.admin.RegistryAdminService;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path storms: every public method of the main registry services is executed against
 * both a deep-stub Redisson client (happy plumbing) and an always-failing one (catch/fallback
 * branches). Behavior assertions live in the dedicated unit/integration suites; this file
 * exists to make failure branches executable and regression-proof.
 */
class RegistryErrorPathStormTest {

    private static Map<Class<?>, Object> hints() {
        Map<Class<?>, Object> hints = new HashMap<>();
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("svc").instanceId("i1").host("h").port(80)
                .protocol(StandardProtocol.HTTP).build();
        hints.put(ServiceInstance.class, instance);
        return hints;
    }

    @Test
    void providerStormOnHappyAndFailingClients() {
        RedissonClient deep = Storms.deep(RedissonClient.class);
        RedisServiceProvider provider = new RedisServiceProvider(deep, new ServiceProviderConfig());
        try {
            assertTrue(Storms.storm(provider, hints()) > 10);
            assertTrue(Storms.storm(provider, null) > 10);
            assertNotNull(provider.getConfig());
            assertNotNull(provider.getHeartbeatConfig());
            assertNotNull(provider.getStateManager());
            assertNotNull(provider.getRegistryKeys());
        } finally {
            provider.stop();
        }

        RedissonClient boom = Storms.exploding(RedissonClient.class);
        RedisServiceProvider failing = Storms.constructing(() -> new RedisServiceProvider(boom, new ServiceProviderConfig()));
        try {
            assertTrue(Storms.storm(failing, hints()) > 10);
        } finally {
            failing.stop();
        }
    }

    @Test
    void consumerStormOnHappyAndFailingClients() {
        RedissonClient deep = Storms.deep(RedissonClient.class);
        RedisServiceConsumer consumer = new RedisServiceConsumer(deep, new ServiceConsumerConfig());
        try {
            assertTrue(Storms.storm(consumer, hints()) > 10);
        } finally {
            consumer.stop();
        }

        RedissonClient boom = Storms.exploding(RedissonClient.class);
        RedisServiceConsumer failing = Storms.constructing(() -> new RedisServiceConsumer(boom, new ServiceConsumerConfig()));
        try {
            assertTrue(Storms.storm(failing, hints()) > 10);
        } finally {
            failing.stop();
        }
    }

    @Test
    void namingServiceAndAdminStorm() {
        RedissonClient deep = Storms.deep(RedissonClient.class);
        RedisNamingService naming = Storms.constructing(() -> new RedisNamingService(deep));
        try {
            assertTrue(Storms.storm(naming, hints()) > 10);
        } finally {
            naming.stop();
        }

        RedissonClient boom = Storms.exploding(RedissonClient.class);
        RegistryAdminService admin = Storms.constructing(() -> new RegistryAdminService(boom, new BaseRedisConfig()));
        assertTrue(Storms.storm(admin, hints()) > 5);
        RegistryAdminService adminHappy = Storms.constructing(() -> new RegistryAdminService(deep, null));
        assertTrue(Storms.storm(adminHappy, hints()) > 5);

        RegistryLuaScriptExecutor lua = Storms.constructing(() -> new RegistryLuaScriptExecutor(boom));
        assertTrue(Storms.storm(lua, hints()) > 5);
        RegistryLuaScriptExecutor luaHappy = Storms.constructing(() -> new RegistryLuaScriptExecutor(deep));
        assertTrue(Storms.storm(luaHappy, hints()) > 5);
    }
}
