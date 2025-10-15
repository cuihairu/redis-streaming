package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class RedisConfigServiceAtomicityTest {

    @Test
    void testSequentialPublishesPreserveHistoryAndCurrent() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);
        RedisConfigService svc = new RedisConfigService(client, new ConfigServiceConfig());
        svc.start();
        try {
            // Publish sequential contents
            for (int i = 0; i < 6; i++) {
                assertTrue(svc.publishConfig(dataId, group, "v" + i));
            }
            // Current should be the last
            assertEquals("v5", svc.getConfig(dataId, group));

            // History top should reflect v4 (previous content)
            List<ConfigHistory> hist = svc.getConfigHistory(dataId, group, 3);
            assertFalse(hist.isEmpty());
            assertEquals("v4", hist.get(0).getContent());
            // Ensure version exists
            assertNotNull(hist.get(0).getVersion());
        } finally {
            svc.stop();
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
