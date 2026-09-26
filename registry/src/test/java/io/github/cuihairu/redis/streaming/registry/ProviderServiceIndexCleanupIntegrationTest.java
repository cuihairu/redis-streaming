package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for B-29 (real Redis): after a cleanup sweep, a service whose
 * heartbeat ZSet became empty must be dropped from the services index, while a service
 * that still has live (non-expired) heartbeats must keep its index entry — even though
 * the sweep runs for both. The old check-then-act version also raced a concurrent
 * registration into orphaning the service entirely; the fix makes the check and the
 * removal one atomic server-side step.
 */
@Tag("integration")
class ProviderServiceIndexCleanupIntegrationTest {

    private RedissonClient redisson;
    private ServiceProviderConfig config;
    private RedisServiceProvider provider;
    private String prefix;

    @BeforeEach
    void setUp() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config rc = new Config();
        rc.useSingleServer().setAddress(redisUrl);
        redisson = Redisson.create(rc);

        prefix = "rgfix-" + UUID.randomUUID().toString().substring(0, 6);
        config = new ServiceProviderConfig(prefix);
        provider = new RedisServiceProvider(redisson, config, new HeartbeatConfig(),
                new MetricsCollectionManager(Collections.emptyList(), new MetricsConfig()));
    }

    @AfterEach
    void tearDown() {
        try {
            redisson.getKeys().deleteByPattern(prefix + ":*");
        } catch (Exception ignore) {
        }
        redisson.shutdown();
    }

    private RSet<String> servicesIndex() {
        return redisson.getSet(config.getRegistryKeys().getServicesIndexKey(),
                org.redisson.client.codec.StringCodec.INSTANCE);
    }

    private RScoredSortedSet<String> heartbeats(String serviceName) {
        return redisson.getScoredSortedSet(config.getRegistryKeys().getServiceHeartbeatsKey(serviceName));
    }

    @Test
    void emptyHeartbeatSetDropsIndexEntryLiveServiceKeepsIt() throws Exception {
        RSet<String> index = servicesIndex();
        index.add("svc-zombie");
        index.add("svc-live");
        // svc-live still has a fresh (non-expired) heartbeat
        heartbeats("svc-live").add(System.currentTimeMillis(), "inst-1");

        invokeCleanupForService("svc-zombie");
        assertFalse(index.contains("svc-zombie"),
                "a service with an empty heartbeat set must leave the index");

        invokeCleanupForService("svc-live");
        assertTrue(index.contains("svc-live"),
                "a service with live heartbeats must keep its index entry");
        assertTrue(heartbeats("svc-live").contains("inst-1"),
                "live heartbeats must not be touched by the sweep");
    }

    private void invokeCleanupForService(String serviceName) throws Exception {
        Method m = RedisServiceProvider.class.getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
        m.setAccessible(true);
        m.invoke(provider, serviceName);
    }
}
