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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** History trimming, removal semantics and multi-listener behaviour on real Redis. */
@Tag("integration")
class ConfigHistoryWatchIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void historyTrimmedAndListenersMultiplexed() throws Exception {
        RedissonClient redis = client();
        String prefix = "hist-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigServiceConfig cfg = new ConfigServiceConfig(prefix, true);
        cfg.setHistorySize(2);
        RedisConfigService service = new RedisConfigService(redis, cfg);
        service.start();
        try {
            String dataId = "app.yml";
            String group = "G1";
            CopyOnWriteArrayList<String> first = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<String> second = new CopyOnWriteArrayList<>();
            io.github.cuihairu.redis.streaming.config.ConfigChangeListener l1 =
                    (id, g, content, version) -> first.add(content);
            io.github.cuihairu.redis.streaming.config.ConfigChangeListener l2 =
                    (id, g, content, version) -> second.add(content);
            service.addListener(dataId, group, l1);
            service.addListener(dataId, group, l2);
            service.addListener(dataId, group, l1); // duplicate registration re-notifies (documented behavior)

            assertTrue(service.publishConfig(dataId, group, "v1"));
            assertTrue(service.publishConfig(dataId, group, "v2"));
            assertTrue(service.publishConfig(dataId, group, "v3"));
            assertEquals("v3", service.getConfig(dataId, group));

            List<ConfigHistory> history = service.getConfigHistory(dataId, group, 10);
            assertFalse(history.isEmpty());
            assertTrue(history.size() <= 3, "history should be trimmed near 2, got " + history.size());
            assertNotNull(history.get(0).getVersion());
            assertNotNull(history.get(0).toString());

            long deadline = System.currentTimeMillis() + 10_000;
            while ((first.size() < 3 || second.size() < 3) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(first.size() >= 3, "first got " + first.size());
            assertTrue(second.size() >= 3, "second got " + second.size());


            service.removeListener(dataId, group, l1);
            service.removeListener(dataId, group, l2);
            service.removeListener("other", group, l1); // unknown key tolerated

            assertTrue(service.removeConfig(dataId, group));
            assertNull(service.getConfig(dataId, group));
            assertFalse(service.removeConfig(dataId, group)); // second removal reports "nothing to remove"
        } finally {
            service.stop();
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
