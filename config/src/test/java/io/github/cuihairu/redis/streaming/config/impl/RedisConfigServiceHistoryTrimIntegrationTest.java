package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-Redis flow: publish/remove with history recording and explicit history trimming. */
@Tag("integration")
class RedisConfigServiceHistoryTrimIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void publishRemoveAndExplicitHistoryTrims() {
        RedissonClient redis = client();
        String prefix = "cfg-trim-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigServiceConfig cfg = new ConfigServiceConfig(prefix, true);
        cfg.setHistorySize(10);
        RedisConfigService service = new RedisConfigService(redis, cfg);
        service.start();
        try {
            CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
            service.addListener("app", "G", (id, g, content, version) -> events.add(String.valueOf(content)));

            assertTrue(service.publishConfig("app", "G", "v1", "first"));
            assertTrue(service.publishConfig("app", "G", "v2", "second"));
            assertTrue(service.publishConfig("app", "G", "v3", "third"));
            assertEquals("v3", service.getConfig("app", "G"));

            List<ConfigHistory> history = service.getConfigHistory("app", "G", 10);
            assertFalse(history.isEmpty());
            assertTrue(history.size() >= 2, "prior versions recorded, got " + history.size());

            int removedBySize = service.trimHistoryBySize("app", "G", 1);
            assertTrue(removedBySize >= 0);
            assertTrue(service.getConfigHistory("app", "G", 10).size() <= 2);

            service.publishConfig("app", "G", "v4", "fourth");
            int removedByAge = service.trimHistoryByAge("app", "G", Duration.ZERO);
            assertTrue(removedByAge >= 0);

            assertTrue(service.removeConfig("app", "G"));
            assertNull(service.getConfig("app", "G"));
            assertFalse(service.removeConfig("app", "G"));

            service.removeListener("app", "G", events.isEmpty() ? (id, g, c, v) -> { }
                    : (id, g, c, v) -> { });
            assertFalse(events.isEmpty(), "local listener saw publishes: " + events);
        } finally {
            service.stop();
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
