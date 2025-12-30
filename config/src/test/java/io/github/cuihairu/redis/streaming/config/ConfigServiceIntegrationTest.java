package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigCenter;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ConfigServiceIntegrationTest {

    @Test
    void testPublishGetHistoryAndEvents() throws Exception {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        ConfigServiceConfig cfg = new ConfigServiceConfig();
        RedisConfigService svc = new RedisConfigService(client, cfg);
        svc.start();
        try {
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            CountDownLatch deleted = new CountDownLatch(1);

            svc.addListener(dataId, group, (d, g, content, version) -> {
                if (content == null) deleted.countDown();
                else if ("v1".equals(content)) first.countDown();
                else if ("v2".equals(content)) second.countDown();
            });

            assertTrue(svc.publishConfig(dataId, group, "v1", "init"));
            assertEquals("v1", svc.getConfig(dataId, group));
            assertTrue(first.await(5, TimeUnit.SECONDS));

            assertTrue(svc.publishConfig(dataId, group, "v2", "update"));
            assertEquals("v2", svc.getConfig(dataId, group));
            assertTrue(second.await(5, TimeUnit.SECONDS));

            // History should have at least one record (v1)
            List<ConfigHistory> history = svc.getConfigHistory(dataId, group, 5);
            assertFalse(history.isEmpty());

            assertTrue(svc.removeConfig(dataId, group));
            assertTrue(deleted.await(5, TimeUnit.SECONDS));
            assertNull(svc.getConfig(dataId, group));

        } finally {
            svc.stop();
            client.shutdown();
        }
    }

    @Test
    void testSubscriberSetNotClearedByOtherClient() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        ConfigServiceConfig cfg = new ConfigServiceConfig();
        RedisConfigService s1 = new RedisConfigService(client, cfg);
        RedisConfigService s2 = new RedisConfigService(client, cfg);
        s1.start(); s2.start();
        try {
            ConfigChangeListener l1 = (d,g,c,v) -> {};
            ConfigChangeListener l2 = (d,g,c,v) -> {};
            s1.addListener(dataId, group, l1);
            s2.addListener(dataId, group, l2);

            String subsKey = cfg.getConfigSubscribersKey(group, dataId);
            RSet<String> subs = client.getSet(subsKey, StringCodec.INSTANCE);
            assertTrue(subs.size() >= 2);

            s1.removeListener(dataId, group, l1);
            // after s1 removal, there should still be at least one subscriber (s2)
            assertTrue(subs.size() >= 1);

            s2.removeListener(dataId, group, l2);
            // after s2 removal, set may be empty
            assertTrue(subs.size() == 0);

        } finally {
            s1.stop(); s2.stop();
            client.shutdown();
        }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }

    // ===== RedisConfigCenter Integration Tests =====

    @Test
    void testRedisConfigCenterLifecycleAndPublish() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            assertFalse(configCenter.isRunning());

            configCenter.start();
            assertTrue(configCenter.isRunning());

            assertTrue(configCenter.publishConfig(dataId, group, "test content", "test description"));
            assertEquals("test content", configCenter.getConfig(dataId, group));
            assertTrue(configCenter.hasConfig(dataId, group));

            // Test metadata
            ConfigCenter.ConfigMetadata metadata = configCenter.getConfigMetadata(dataId, group);
            assertNotNull(metadata);
            assertEquals("1.0", metadata.getVersion());
            assertEquals("Configuration for " + group + ":" + dataId, metadata.getDescription());
            assertTrue(metadata.getSize() > 0);

            // Test history - publish again to create history (first publish doesn't create history)
            configCenter.publishConfig(dataId, group, "updated content", "updated description");
            List<ConfigHistory> history = configCenter.getConfigHistory(dataId, group, 10);
            assertFalse(history.isEmpty());

        } finally {
            configCenter.stop();
            assertFalse(configCenter.isRunning());
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterRemoveConfig() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            assertTrue(configCenter.publishConfig(dataId, group, "content"));
            assertTrue(configCenter.hasConfig(dataId, group));

            assertTrue(configCenter.removeConfig(dataId, group));
            assertFalse(configCenter.hasConfig(dataId, group));
            assertNull(configCenter.getConfig(dataId, group));

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterListener() throws Exception {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            CountDownLatch latch = new CountDownLatch(1);
            ConfigChangeListener listener = (d, g, content, version) -> {
                if ("notify-content".equals(content)) {
                    latch.countDown();
                }
            };

            configCenter.addListener(dataId, group, listener);
            assertTrue(configCenter.publishConfig(dataId, group, "notify-content"));

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            configCenter.removeListener(dataId, group, listener);

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterPublishConfigWithoutDescription() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            assertTrue(configCenter.publishConfig(dataId, group, "content"));
            assertEquals("content", configCenter.getConfig(dataId, group));

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterPublishNullContent() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            // Publish null content should be handled (may remove or update)
            assertTrue(configCenter.publishConfig(dataId, group, null));

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterPublishEmptyContent() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            assertTrue(configCenter.publishConfig(dataId, group, ""));
            assertEquals("", configCenter.getConfig(dataId, group));

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterGetNonExistentConfig() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            assertNull(configCenter.getConfig(dataId, group));
            assertFalse(configCenter.hasConfig(dataId, group));

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterRemoveNonExistentConfig() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            // Removing non-existent config should return false
            assertFalse(configCenter.removeConfig(dataId, group));

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterGetEmptyHistory() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            List<ConfigHistory> history = configCenter.getConfigHistory(dataId, group, 10);
            assertTrue(history.isEmpty());

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterMultipleUpdates() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            assertTrue(configCenter.publishConfig(dataId, group, "v1"));
            assertEquals("v1", configCenter.getConfig(dataId, group));

            assertTrue(configCenter.publishConfig(dataId, group, "v2"));
            assertEquals("v2", configCenter.getConfig(dataId, group));

            assertTrue(configCenter.publishConfig(dataId, group, "v3"));
            assertEquals("v3", configCenter.getConfig(dataId, group));

            List<ConfigHistory> history = configCenter.getConfigHistory(dataId, group, 10);
            assertTrue(history.size() >= 2);

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterMetadataForNonExistent() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            ConfigCenter.ConfigMetadata metadata = configCenter.getConfigMetadata(dataId, group);
            assertNotNull(metadata);
            assertEquals(0, metadata.getSize());

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterStartStopMultipleTimes() {
        RedissonClient client = createClient();

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();
            assertTrue(configCenter.isRunning());

            configCenter.start(); // Should be idempotent
            assertTrue(configCenter.isRunning());

            configCenter.stop();
            assertFalse(configCenter.isRunning());

            configCenter.stop(); // Should be idempotent
            assertFalse(configCenter.isRunning());

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterOperationBeforeStart() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            // Operations before start should throw IllegalStateException
            assertThrows(IllegalStateException.class, () -> configCenter.publishConfig(dataId, group, "content"));
            assertThrows(IllegalStateException.class, () -> configCenter.getConfig(dataId, group));
            assertThrows(IllegalStateException.class, () -> configCenter.removeConfig(dataId, group));

        } finally {
            client.shutdown();
        }
    }

    @Test
    void testRedisConfigCenterGetConfigHistoryWithSizeLimit() {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigCenter configCenter = new RedisConfigCenter(client);
        try {
            configCenter.start();

            // Publish multiple versions
            for (int i = 1; i <= 5; i++) {
                configCenter.publishConfig(dataId, group, "v" + i);
            }

            // Get limited history
            List<ConfigHistory> history = configCenter.getConfigHistory(dataId, group, 3);
            assertTrue(history.size() <= 3);

        } finally {
            configCenter.stop();
            client.shutdown();
        }
    }
}
