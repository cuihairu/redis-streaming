package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class RedisTokenBucketRateLimiterIntegrationExample {
    private RedissonClient createClient() {
        String addr = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(addr);
        return Redisson.create(cfg);
    }

    @Test
    void tokenBucketAllowsBurstThenRefills() {
        RedissonClient client = createClient();
        try {
            RateLimiter rl = new RedisTokenBucketRateLimiter(client, "streaming:tb:test", 3.0, 1.0);
            String key = "acct:tb";
            long t = System.currentTimeMillis();
            assertTrue(rl.allowAt(key, t));
            assertTrue(rl.allowAt(key, t));
            assertTrue(rl.allowAt(key, t));
            assertFalse(rl.allowAt(key, t));
            assertTrue(rl.allowAt(key, t + 1000));
        } finally {
            client.shutdown();
        }
    }
}
