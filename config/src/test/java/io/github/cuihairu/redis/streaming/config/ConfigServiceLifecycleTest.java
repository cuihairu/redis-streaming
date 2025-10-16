package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ConfigServiceLifecycleTest {

    @Test
    void testStartStopIdempotencyAndRemoveNonexistent() {
        RedissonClient client = createClient();
        RedisConfigService svc = new RedisConfigService(client, new ConfigServiceConfig());
        try {
            // start twice
            svc.start();
            assertTrue(svc.isRunning());
            svc.start();
            assertTrue(svc.isRunning());

            // remove nonexistent returns false
            assertFalse(svc.removeConfig("no-id", "no-group"));

            // stop twice
            svc.stop();
            assertFalse(svc.isRunning());
            svc.stop();
            assertFalse(svc.isRunning());
        } finally {
            try { svc.stop(); } catch (Exception ignore) {}
            client.shutdown();
        }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}
