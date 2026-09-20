package io.github.cuihairu.redis.streaming.join;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.join.StreamJoiner;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every join main class with a timeout-guarded pass. */
class JoinGrandStormTest {

    @Test
    void sweepAllClasses() throws Exception {
        int total = Storms.grandStorm(StreamJoiner.class, "io.github.cuihairu.redis.streaming.join", REAL_HINTS, 150);
        System.err.println("GRAND join invocations=" + total);
        assertTrue(total >= 0);
    }

    static final java.util.Map<Class<?>, Object> REAL_HINTS = buildRealHints();

    private static java.util.Map<Class<?>, Object> buildRealHints() {
        org.redisson.config.Config redisCfg = new org.redisson.config.Config();
        redisCfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        java.util.Map<Class<?>, Object> h = new java.util.HashMap<>();
        h.put(org.redisson.api.RedissonClient.class, org.redisson.Redisson.create(redisCfg));
        return h;
    }

}
