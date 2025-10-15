package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ConfigServiceListenerJoinTest {

    @Test
    void testListenerJoiningMidStreamGetsLatest() throws Exception {
        RedissonClient client = createClient();
        String group = "gj-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "dj-" + UUID.randomUUID().toString().substring(0, 6);
        RedisConfigService svc = new RedisConfigService(client, new ConfigServiceConfig());
        svc.start();
        try {
            // Publish two versions
            assertTrue(svc.publishConfig(dataId, group, "v1"));
            assertTrue(svc.publishConfig(dataId, group, "v2"));

            CountDownLatch latch = new CountDownLatch(1);
            final String[] seen = {null};
            svc.addListener(dataId, group, (d,g,c,v) -> { seen[0] = c; latch.countDown(); });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            // Listener's initial callback should get latest content (v2)
            assertEquals("v2", seen[0]);

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
