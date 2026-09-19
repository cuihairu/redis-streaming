package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every checkpoint main class with a timeout-guarded pass. */
class CheckpointGrandStormTest {

    @Test
    void sweepAllClasses() throws Exception {
        java.util.Map<Class<?>, Object> hints = new java.util.HashMap<>();
        org.redisson.config.Config redisCfg = new org.redisson.config.Config();
        redisCfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        org.redisson.api.RedissonClient real = org.redisson.Redisson.create(redisCfg);
        hints.put(org.redisson.api.RedissonClient.class, real);
        int total = Storms.grandStorm(DefaultCheckpoint.class, "io.github.cuihairu.redis.streaming.checkpoint", hints, 150);
        System.err.println("GRAND-REDIS checkpoint invocations=" + total);
        real.shutdown();
    }
}
