package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for B-28 (Lua paths): with historySize=0 the history trim ran
 * {@code LTRIM hist 0 maxhist-1} = {@code LTRIM hist 0 -1}, which Redis defines as
 * "keep everything" — the exact opposite of the requested "keep no history", so every
 * publish/remove grew the history list without bound. Both the publish and the remove
 * Lua scripts must skip the history write entirely when maxHistorySize is 0.
 */
@Tag("integration")
class ConfigHistorySizeZeroIntegrationTest {

    @Test
    void historySizeZeroKeepsNoHistoryOnPublishAndRemove() {
        RedissonClient client = createClient();
        String group = "z0-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "zd-" + UUID.randomUUID().toString().substring(0, 6);
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setHistorySize(0);
        RedisConfigService svc = new RedisConfigService(client, cfg);
        svc.start();
        try {
            assertTrue(svc.publishConfig(dataId, group, "v0"));
            assertTrue(svc.publishConfig(dataId, group, "v1"));
            assertEquals("v1", svc.getConfig(dataId, group),
                    "config updates must work normally with historySize=0");
            assertEquals(0, historySize(client, cfg, group, dataId),
                    "publish must not record history when historySize=0 (old code: LTRIM 0 -1 kept all)");

            assertTrue(svc.removeConfig(dataId, group));
            assertEquals(0, historySize(client, cfg, group, dataId),
                    "remove must not record history when historySize=0");
        } finally {
            svc.stop();
            client.shutdown();
        }
    }

    @Test
    void historySizeOneKeepsSingleRecord() {
        RedissonClient client = createClient();
        String group = "z1-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "z1d-" + UUID.randomUUID().toString().substring(0, 6);
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setHistorySize(1);
        RedisConfigService svc = new RedisConfigService(client, cfg);
        svc.start();
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(svc.publishConfig(dataId, group, "v" + i));
            }
            List<ConfigHistory> hist = svc.getConfigHistory(dataId, group, 10);
            assertEquals(1, hist.size(),
                    "guard must not affect positive historySize values");
        } finally {
            svc.stop();
            client.shutdown();
        }
    }

    private static int historySize(RedissonClient client, ConfigServiceConfig cfg, String group, String dataId) {
        RList<String> list = client.getList(cfg.getConfigHistoryKey(group, dataId), StringCodec.INSTANCE);
        int size = list.size();
        list.delete();
        return size;
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}
