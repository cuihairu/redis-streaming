package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-07 regression: a publisher delivered its own change to in-process listeners twice —
 * once synchronously and once via the pub/sub loopback. Every change must notify a
 * listener exactly once, whether the listener lives in the publishing JVM or another one.
 */
@Tag("integration")
class ConfigChangeSingleDeliveryIntegrationTest {

    private RedissonClient client;
    private RedisConfigService publisher;
    private RedisConfigService subscriberService;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        publisher = new RedisConfigService(client);
        subscriberService = new RedisConfigService(client);
        publisher.start();
        subscriberService.start();
    }

    @AfterEach
    void tearDown() {
        try {
            publisher.stop();
            subscriberService.stop();
        } catch (Exception ignore) {
        }
        client.shutdown();
    }

    @Test
    void publishingJvmNotifiesItsOwnListenersExactlyOnce() throws Exception {
        String dataId = "b07-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

        List<String> deliveries = new CopyOnWriteArrayList<>();
        ConfigChangeListener listener = (d, g, c, v) -> deliveries.add(c);
        publisher.addListener(dataId, group, listener);
        // give the subscription a moment to establish so the loopback is really in flight
        Thread.sleep(300);

        assertTrue(publisher.publishConfig(dataId, group, "v1"));
        // the local synchronous delivery has happened by the time publish returns
        assertEquals(1, deliveries.size(),
                "local delivery must happen synchronously with the publish");

        // the loopback arrives (or is skipped) asynchronously — allow it to land
        Thread.sleep(1000);
        assertEquals(1, deliveries.size(),
                "the pub/sub loopback must not re-deliver the publisher's own event (old code fired twice)");
        assertEquals("v1", deliveries.get(0));
    }

    @Test
    void otherJvmListenerIsNotifiedExactlyOncePerChange() throws Exception {
        String dataId = "b07x-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

        List<String> deliveries = new CopyOnWriteArrayList<>();
        ConfigChangeListener listener = (d, g, c, v) -> deliveries.add(c);
        subscriberService.addListener(dataId, group, listener);
        Thread.sleep(300);

        assertTrue(publisher.publishConfig(dataId, group, "v1"));
        Thread.sleep(1000);

        assertEquals(List.of("v1"), deliveries,
                "a remote listener must see the change exactly once");
    }

    @Test
    void removalNotifiesOnceAndListenersStopAfterRemoval() throws Exception {
        String dataId = "b07r-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g-" + UUID.randomUUID().toString().substring(0, 4);

        List<String> deliveries = new CopyOnWriteArrayList<>();
        ConfigChangeListener listener = (d, g, c, v) -> deliveries.add(c);
        publisher.addListener(dataId, group, listener);
        Thread.sleep(300);

        assertTrue(publisher.publishConfig(dataId, group, "v1"));
        Thread.sleep(1000);
        assertEquals(1, deliveries.size(), "exactly one notification for the publish (B-07)");

        publisher.removeListener(dataId, group, listener);
        assertTrue(publisher.publishConfig(dataId, group, "v2"));
        Thread.sleep(1000);
        assertEquals(1, deliveries.size(), "no notification after the listener was removed");
    }
}
