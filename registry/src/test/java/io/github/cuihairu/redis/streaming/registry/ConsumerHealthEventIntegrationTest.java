package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression for B-04: with health checking enabled, a discovered instance
 * that goes down must produce a HEALTH_FAILURE event and isInstanceHealthy must report
 * it; unsubscribe must stop the instance's checker (old code: key mismatch made all
 * three silently no-op).
 */
@Tag("integration")
class ConsumerHealthEventIntegrationTest {

    private static final String SERVICE = "svc-health-b04";

    private static RedissonClient redissonClient;
    private RedisServiceProvider provider;
    private RedisServiceConsumer consumer;

    @BeforeAll
    static void setupRedis() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void teardownRedis() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @BeforeEach
    void setup() {
        redissonClient.getKeys().flushdb();

        provider = new RedisServiceProvider(redissonClient);
        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(true);
        config.setHealthCheckInterval(1);
        config.setHealthCheckTimeUnit(TimeUnit.SECONDS);
        config.setHealthCheckTimeout(500);
        consumer = new RedisServiceConsumer(redissonClient, config);

        provider.start();
        consumer.start();
    }

    @AfterEach
    void cleanup() {
        try {
            if (provider != null) {
                provider.stop();
            }
        } finally {
            if (consumer != null) {
                consumer.stop();
            }
        }
    }

    @Test
    void healthFailureReachesListenerAndUnsubscribeStopsChecker() throws Exception {
        // unreachable port: connection refused on localhost, so the checker fails fast
        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName(SERVICE)
                .instanceId("i1")
                .host("127.0.0.1")
                .port(1)
                .protocol(StandardProtocol.TCP)
                .ephemeral(true)
                .build();
        provider.register(instance);
        Thread.sleep(500);

        LinkedBlockingDeque<ServiceChangeAction> events = new LinkedBlockingDeque<>();

        ServiceChangeListener listener = (serviceName, action, changed, all) -> events.offer(action);
        consumer.subscribe(SERVICE, listener);

        // discover registers the health checker; since B-08 the first check runs
        // asynchronously, and a discovery CURRENT event may be queued ahead of it —
        // drain until the first probe's HEALTH_FAILURE arrives
        assertEquals(1, consumer.discover(SERVICE).size());

        ServiceChangeAction action = null;
        long healthDeadline = System.currentTimeMillis() + 20_000;
        while (action != ServiceChangeAction.HEALTH_FAILURE) {
            long remaining = healthDeadline - System.currentTimeMillis();
            action = events.poll(Math.max(1, remaining), TimeUnit.MILLISECONDS);
            assertNotNull(action, "expected a health failure event within 20s");
        }
        assertFalse(consumer.isInstanceHealthy("i1"),
                "bare instanceId lookup must resolve to the checker's uniqueId key");

        // unsubscribe must remove the checker (old code leaked it forever)
        consumer.unsubscribe(SERVICE, listener);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && consumer.getHealthCheckManager().getHealthCheckerCount() > 0) {
            Thread.sleep(100);
        }
        assertEquals(0, consumer.getHealthCheckManager().getHealthCheckerCount(),
                "checker must be stopped via its uniqueId key");
        // a final in-flight check may still have queued one event before the unsubscribe
        events.clear();
    }
}
