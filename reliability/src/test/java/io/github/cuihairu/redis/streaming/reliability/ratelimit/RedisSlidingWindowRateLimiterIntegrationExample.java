package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test example for RedisSlidingWindowRateLimiter.
 * Requires a running Redis at redis://127.0.0.1:6379 (override via REDIS_URL).
 */
@Tag("integration")
class RedisSlidingWindowRateLimiterIntegrationExample {

    private RedissonClient createClient() {
        String addr = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(addr);
        return Redisson.create(cfg);
    }

    @Test
    void allowsWithinLimitAndBlocksBeyond() {
        RedissonClient client = createClient();
        try {
            RateLimiter rl = new RedisSlidingWindowRateLimiter(client, "streaming:rl:test", 500, 2);
            String key = "acct:123";

            long t = System.currentTimeMillis();
            assertTrue(rl.allowAt(key, t));
            assertTrue(rl.allowAt(key, t + 1));
            assertFalse(rl.allowAt(key, t + 2));

            // After half a second window passes, should allow again
            assertTrue(rl.allowAt(key, t + 501));
        } finally {
            client.shutdown();
        }
    }
}
