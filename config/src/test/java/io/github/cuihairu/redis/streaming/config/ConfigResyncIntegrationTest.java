package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-06 regression: config change notifications travel over pub/sub only, so a change
 * published while this JVM's subscription was disconnected was lost forever — the
 * listener held the stale content until the next publish of the same dataId. The
 * resync poll re-reads the authoritative state from Redis and re-delivers it, bounding
 * the staleness of a missed notification to the configured interval.
 *
 * <p>The missed notification is simulated by mutating the config hash directly (what
 * publishConfig's Lua stores) without emitting any pub/sub message.</p>
 */
@Tag("integration")
class ConfigResyncIntegrationTest {

    private RedissonClient client;
    private RedisConfigService service;
    private ConfigServiceConfig cfg;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        cfg = new ConfigServiceConfig();
        cfg.setResyncIntervalMs(200);
        service = new RedisConfigService(client, cfg);
        service.start();
    }

    @AfterEach
    void tearDown() {
        try {
            service.stop();
        } catch (Exception ignore) {
        }
        client.shutdown();
    }

    private RMap<String, String> rawMap(String group, String dataId) {
        return client.getMap(cfg.getConfigKey(group, dataId), StringCodec.INSTANCE);
    }

    /** Writes the config hash the way publishConfig's Lua would, but with no pub/sub event. */
    private void missedWrite(String group, String dataId, String content) {
        RMap<String, String> raw = rawMap(group, dataId);
        raw.put("content", content);
        raw.put("version", "9999999-0");
        raw.put("updateTime", String.valueOf(System.currentTimeMillis()));
    }

    private static void awaitValue(List<String> deliveries, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!deliveries.contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    void missedPublishIsReconciledFromRedisExactlyOnce() throws Exception {
        String dataId = "b06a-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

        List<String> deliveries = new CopyOnWriteArrayList<>();
        service.addListener(dataId, group, (d, g, c, v) -> deliveries.add(c));
        Thread.sleep(300);

        assertTrue(service.publishConfig(dataId, group, "v1"));
        awaitValue(deliveries, "v1", 2000);
        assertEquals(List.of("v1"), deliveries, "baseline publish delivered once (B-07)");

        missedWrite(group, dataId, "v2");   // the notification this JVM never received
        awaitValue(deliveries, "v2", 5000);
        assertTrue(deliveries.contains("v2"), "missed change must be re-delivered by the resync poll");

        Thread.sleep(700);                  // several more intervals: no double delivery
        assertEquals(List.of("v1", "v2"), deliveries, "reconciled state delivered exactly once");
    }

    @Test
    void missedRemovalIsReconciledAsNullContent() throws Exception {
        String dataId = "b06r-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

        List<String> deliveries = new CopyOnWriteArrayList<>();
        service.addListener(dataId, group, (d, g, c, v) -> deliveries.add(c));
        Thread.sleep(300);

        assertTrue(service.publishConfig(dataId, group, "v1"));
        awaitValue(deliveries, "v1", 2000);

        rawMap(group, dataId).delete();     // removal whose notification never arrived
        long deadline = System.currentTimeMillis() + 5000;
        while (!deliveries.contains(null) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(deliveries.contains(null), "missed removal must be reconciled as null content, got " + deliveries);
    }

    @Test
    void noResyncNoiseWhenNoNotificationWasMissed() throws Exception {
        String dataId = "b06n-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

        List<String> deliveries = new CopyOnWriteArrayList<>();
        service.addListener(dataId, group, (d, g, c, v) -> deliveries.add(c));
        Thread.sleep(300);

        assertTrue(service.publishConfig(dataId, group, "v1"));
        assertTrue(service.publishConfig(dataId, group, "v2"));
        awaitValue(deliveries, "v2", 2000);

        Thread.sleep(1000);                 // five intervals with nothing missed
        assertEquals(List.of("v1", "v2"), deliveries, "the poll must stay silent when pub/sub delivered everything");
    }

    @Test
    void resyncDisabledByZeroIntervalStaysPurelyReactive() throws Exception {
        ConfigServiceConfig off = new ConfigServiceConfig();
        off.setResyncIntervalMs(0);
        RedisConfigService reactive = new RedisConfigService(client, off);
        reactive.start();
        try {
            String dataId = "b06z-" + UUID.randomUUID().toString().substring(0, 8);
            String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

            List<String> deliveries = new CopyOnWriteArrayList<>();
            reactive.addListener(dataId, group, (d, g, c, v) -> deliveries.add(c));
            Thread.sleep(300);

            missedWrite(group, dataId, "v2");
            Thread.sleep(1500);
            assertFalse(deliveries.contains("v2"),
                    "interval 0 disables reconciliation entirely (old purely-reactive behavior)");
        } finally {
            reactive.stop();
        }
    }
}
