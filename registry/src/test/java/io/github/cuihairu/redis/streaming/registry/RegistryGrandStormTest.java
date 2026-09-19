package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every registry main class with a timeout-guarded pass. */
class RegistryGrandStormTest {

    @Test
    void sweepAllClasses() throws Exception {
        java.util.Map<Class<?>, Object> hints = new java.util.HashMap<>();
        org.redisson.config.Config redisCfg = new org.redisson.config.Config();
        redisCfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        org.redisson.api.RedissonClient real = org.redisson.Redisson.create(redisCfg);
        hints.put(org.redisson.api.RedissonClient.class, real);
        int total = Storms.grandStorm(BaseRedisConfig.class, "io.github.cuihairu.redis.streaming.registry", hints, 150);
        System.err.println("GRAND-REDIS registry invocations=" + total);
        real.shutdown();
    }
}
