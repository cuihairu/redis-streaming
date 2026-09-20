package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Edge branches of publishConfig/removeConfig/history on real Redis. */
@Tag("integration")
class ConfigRemovalEdgeIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void removeAndDescribeEdges() {
        RedissonClient redis = client();
        String prefix = "cfg-edge-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigServiceConfig cfg = new ConfigServiceConfig(prefix, true);
        cfg.setHistorySize(3);
        RedisConfigService service = new RedisConfigService(redis, cfg);
        service.start();
        try {
            assertFalse(service.removeConfig("never", "G"), "removing missing config reports false");
            assertTrue(service.publishConfig("x", "G", null), "null content is tolerated (stored as empty)");

            assertTrue(service.publishConfig("edge", "G", "one", "first release"));
            assertTrue(service.publishConfig("edge", "G", "two", "second release"));
            assertTrue(service.publishConfig("edge", "G", "three", "third release"));
            assertTrue(service.publishConfig("edge", "G", "four", "fourth release"));

            List<ConfigHistory> history = service.getConfigHistory("edge", "G", 10);
            assertTrue(history.size() <= 4, "history bounded near configured window: " + history.size());
            assertEquals("four", service.getConfig("edge", "G", "def"));
            assertEquals("def", service.getConfig("missing-key", "G", "def"));

            assertTrue(service.removeConfig("edge", "G"));
            assertFalse(service.removeConfig("edge", "G"));
        } finally {
            service.stop();
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
