package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
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
}
