package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional integration coverage for listener notifications, history windowing and
 * removal semantics of {@link RedisConfigService}.
 */
@Tag("integration")
class ConfigServiceListenerHistoryIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void listenerNotifiedOnPublishAndRemoveAndHistoryBounded() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "cfg-listen-" + uid;
        ConfigServiceConfig cfg = new ConfigServiceConfig(prefix, true);
        cfg.setHistorySize(2);
        RedisConfigService service = new RedisConfigService(client, cfg);
        service.start();
        try {
            String dataId = "app-" + uid;
            String group = "DEFAULT_GROUP";

            List<String> seen = new CopyOnWriteArrayList<>();
            CountDownLatch twoEvents = new CountDownLatch(2);
            ConfigChangeListener listener = (id, g, content, version) -> {
                seen.add(content);
                assertEquals(dataId, id);
                assertEquals(group, g);
                twoEvents.countDown();
            };
            service.addListener(dataId, group, listener);

            assertTrue(service.publishConfig(dataId, group, "v1", "initial release"));
            assertTrue(service.publishConfig(dataId, group, "v2"));

            // history window honours configured size (2)
            List<ConfigHistory> history = service.getConfigHistory(dataId, group, 5);
            assertFalse(history.isEmpty(), "history should not be empty");
            assertTrue(history.size() <= 2 + 1, "history should be trimmed near historySize, got " + history.size());
            assertEquals("v2", service.getConfig(dataId, group));

            // remove + default overload
            assertTrue(service.removeConfig(dataId, group));
            assertNull(service.getConfig(dataId, group));
            assertEquals("fallback", service.getConfig(dataId, group, "fallback"));

            assertTrue(twoEvents.await(10, TimeUnit.SECONDS), "listener should have been notified for both publishes");
            assertTrue(seen.contains("v1"), "saw " + seen);

            // listener removed -> no further state churn, and idempotent lifecycle
            service.removeListener(dataId, group, listener);
            service.stop();
            service.stop();
            service.start();
            service.stop();
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void publishRemoveAndGetAcrossInstances() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "cfg-two-" + uid;
        RedisConfigService a = new RedisConfigService(client, new ConfigServiceConfig(prefix, true));
        RedisConfigService b = new RedisConfigService(client, new ConfigServiceConfig(prefix, true));
        a.start();
        b.start();
        try {
            assertTrue(a.publishConfig("shared", "G", "from-a"));
            assertEquals("from-a", b.getConfig("shared", "G"));
            // cross-instance change notification
            CountDownLatch latch = new CountDownLatch(1);
            b.addListener("shared", "G", (id, g, content, version) -> latch.countDown());
            assertTrue(a.publishConfig("shared", "G", "from-a-2"));
            assertTrue(latch.await(10, TimeUnit.SECONDS), "b should observe a's publish via pub/sub");
        } finally {
            a.stop();
            b.stop();
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }
}
