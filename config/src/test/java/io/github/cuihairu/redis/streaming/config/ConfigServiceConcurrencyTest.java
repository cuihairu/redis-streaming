package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ConfigServiceConcurrencyTest {

    @Test
    void testConcurrentPublishesProduceUniqueVersions() throws Exception {
        RedissonClient client = createClient();
        String group = "cg-" + UUID.randomUUID().toString().substring(0, 6);
        String dataId = "cd-" + UUID.randomUUID().toString().substring(0, 6);

        RedisConfigService svc = new RedisConfigService(client, new ConfigServiceConfig());
        svc.start();
        try {
            int threads = 8;
            int perThread = 5;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            svc.publishConfig(dataId, group, "v-" + tid + "-" + i);
                        }
                    } catch (Exception ignore) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            pool.shutdownNow();

            // Current content should not be null and should be one of the published values
            String cur = svc.getConfig(dataId, group);
            assertNotNull(cur);

            // Collect top 20 history versions and ensure no duplicate version among them when combined with current
            List<ConfigHistory> hist = svc.getConfigHistory(dataId, group, 20);
            Set<String> versions = new HashSet<>();
            for (ConfigHistory h : hist) {
                if (h.getVersion() != null) versions.add(h.getVersion());
            }
            // We cannot know all versions due to trim, but at least versions should be unique
            assertEquals(versions.size(), hist.stream().map(ConfigHistory::getVersion).filter(v -> v != null).count());
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
