package io.github.cuihairu.redis.streaming.state;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.state.redis.RedisValueState;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every state main class with a timeout-guarded pass. */
@Tag("integration")
class StateGrandStormTest {

    @Test
    void sweepAllClasses() throws Exception {
        java.util.Map<Class<?>, Object> hints = new java.util.HashMap<>();
        org.redisson.config.Config redisCfg = new org.redisson.config.Config();
        redisCfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        org.redisson.api.RedissonClient real = org.redisson.Redisson.create(redisCfg);
        hints.put(org.redisson.api.RedissonClient.class, real);
        int total = Storms.grandStorm(RedisValueState.class, "io.github.cuihairu.redis.streaming.state", hints, 2000);
        System.err.println("GRAND-REDIS state invocations=" + total);
        assertTrue(total > 0);
        real.shutdown();
    }
}
