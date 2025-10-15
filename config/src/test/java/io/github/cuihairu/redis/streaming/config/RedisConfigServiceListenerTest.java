package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class RedisConfigServiceListenerTest {

    @Test
    void testTwoClientsMaintainSubscribersSet() throws Exception {
        RedissonClient client = createClient();
        String group = "g-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "d-" + UUID.randomUUID().toString().substring(0, 6);
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        RedisConfigService s1 = new RedisConfigService(client, cfg);
        RedisConfigService s2 = new RedisConfigService(client, cfg);
        s1.start(); s2.start();
        try {
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);
            ConfigChangeListener l1 = (d,g,c,v) -> latch1.countDown();
            ConfigChangeListener l2 = (d,g,c,v) -> latch2.countDown();
            s1.addListener(dataId, group, l1);
            s2.addListener(dataId, group, l2);

            // publish one to trigger both
            assertTrue(s1.publishConfig(dataId, group, "v"));
            assertTrue(latch1.await(5, TimeUnit.SECONDS));
            assertTrue(latch2.await(5, TimeUnit.SECONDS));

            // both subscribers recorded
            RSet<String> subs = client.getSet(cfg.getConfigSubscribersKey(group, dataId), StringCodec.INSTANCE);
            assertTrue(subs.size() >= 2);

            // remove one, the other remains
            s1.removeListener(dataId, group, l1);
            assertTrue(subs.size() >= 1);

            s2.removeListener(dataId, group, l2);
            assertTrue(subs.size() == 0 || subs.isEmpty());

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
