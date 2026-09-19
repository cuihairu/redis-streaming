package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.admin.RegistryAdminService;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Tag;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;

/** Deep (private method, timeout-guarded) storms for the registry services against real Redis. */
@Tag("integration")
class RegistryDeepStormTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void providerConsumerAdminDeepStorm() throws Exception {
        RedissonClient redis = client();
        RedisServiceProvider provider = new RedisServiceProvider(redis, new ServiceProviderConfig());
        RedisServiceConsumer consumer = new RedisServiceConsumer(redis, new ServiceConsumerConfig());
        RegistryAdminService admin = new RegistryAdminService(redis, new BaseRedisConfig());
        RegistryLuaScriptExecutor lua = new RegistryLuaScriptExecutor(redis);
        try {
            assertTrue(Storms.stormDeep(provider, java.util.Map.of(), 400) > 8);
            assertTrue(Storms.stormDeep(consumer, java.util.Map.of(), 400) > 8);
            assertTrue(Storms.stormDeep(admin, java.util.Map.of(), 400) > 5);
            assertTrue(Storms.stormDeep(lua, java.util.Map.of(), 400) > 5);
        } finally {
            provider.stop();
            consumer.stop();
            redis.shutdown();
        }
    }
}
