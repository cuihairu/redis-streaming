package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ConfigHistoryRetentionTest {

    @Test
    void testRetentionBySize() {
        RedissonClient client = createClient();
        String group = "rs-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "rd-" + UUID.randomUUID().toString().substring(0, 6);
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setHistorySize(3);
        RedisConfigService svc = new RedisConfigService(client, cfg);
        svc.start();
        try {
            for (int i = 0; i < 6; i++) assertTrue(svc.publishConfig(dataId, group, "v" + i));
            // history should keep at most 3 records (v5 current, history top v4..v2)
            List<ConfigHistory> hist = svc.getConfigHistory(dataId, group, 10);
            assertTrue(hist.size() <= 3);

            // Now trim to size 1 via admin API
            int removed = svc.trimHistoryBySize(dataId, group, 1);
            assertTrue(removed >= 0);
            hist = svc.getConfigHistory(dataId, group, 10);
            assertTrue(hist.size() <= 1);
        } finally {
            svc.stop();
            client.shutdown();
        }
    }

    @Test
    void testRetentionByAge() throws Exception {
        RedissonClient client = createClient();
        String group = "rt-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "rt-" + UUID.randomUUID().toString().substring(0, 6);
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setHistorySize(10);
        RedisConfigService svc = new RedisConfigService(client, cfg);
        svc.start();
        try {
            assertTrue(svc.publishConfig(dataId, group, "v1"));
            Thread.sleep(50);
            assertTrue(svc.publishConfig(dataId, group, "v2"));
            Thread.sleep(50);
            assertTrue(svc.publishConfig(dataId, group, "v3"));

            // Trim to age that removes first entry
            int removed = svc.trimHistoryByAge(dataId, group, Duration.ofMillis(60));
            assertTrue(removed >= 1);
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
