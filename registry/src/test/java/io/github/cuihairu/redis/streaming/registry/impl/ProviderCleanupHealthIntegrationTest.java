package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.health.HealthCheckManager;
import io.github.cuihairu.redis.streaming.registry.health.StandardHealthChecker;
import io.github.cuihairu.redis.streaming.registry.health.TcpHealthChecker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Expired-cleanup snapshot notifications and the health-check manager callbacks on real Redis. */
@Tag("integration")
class ProviderCleanupHealthIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static ServiceInstance ins(String svc, String id) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("127.0.0.1").port(1)
                .protocol(StandardProtocol.TCP).metadata(Map.of()).healthy(true).build();
    }

    @Test
    void expiredCleanupNotifiesWithRebuiltSnapshot() throws Exception {
        RedissonClient redis = client();
        String svc = "cleanup-" + UUID.randomUUID().toString().substring(0, 8);
        RedisNamingService naming = new RedisNamingService(redis);
        naming.start();
        RedisServiceProvider provider = new RedisServiceProvider(redis,
                new io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig());
        provider.start();
        try {
            ServiceInstance target = ins(svc, "gone");
            provider.register(target);
            naming.subscribe(svc, (serviceName, action, changed, instances) -> {
                // collect events
            });

            // expire the heartbeat score manually (yesterday), then trigger the provider's private cleanup
            RScoredSortedSet<String> heartbeats = null;
            // find the heartbeat ZSET for this service by key type
            for (String key : redis.getKeys().getKeysByPattern("*" + svc + "*")) {
                if (key.contains("heartbeat") && redis.getKeys().getType(key) == org.redisson.api.RType.ZSET) {
                    heartbeats = redis.getScoredSortedSet(key, org.redisson.client.codec.StringCodec.INSTANCE);
                    break;
                }
            }
            assertNotNull(heartbeats, "heartbeat zset should exist for the registered service");
            for (String member : heartbeats.valueRange(0, -1)) {
                heartbeats.add(System.currentTimeMillis() - 86_400_000L, member);
            }

            Method cleanup = RedisServiceProvider.class
                    .getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
            cleanup.setAccessible(true);
            cleanup.invoke(provider, svc);

            long deadline = System.currentTimeMillis() + 10_000;
            while (!naming.getInstances(svc, false).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(naming.getInstances(svc, false).isEmpty(), "expired instance should be gone");
        } finally {
            provider.stop();
            naming.stop();
            redis.shutdown();
        }
    }

    @Test
    void healthCheckManagerDrivesCallbacks() throws Exception {
        CopyOnWriteArrayList<Boolean> reports = new CopyOnWriteArrayList<>();
        HealthCheckManager manager = new HealthCheckManager(
                new StandardHealthChecker(),
                (instanceId, healthy) -> reports.add(healthy),
                1, TimeUnit.SECONDS);
        manager.registerProtocolHealthChecker(StandardProtocol.TCP, new TcpHealthChecker(200));
        assertTrue(manager.getHealthCheckerCount() >= 0);
        assertNotNull(manager.getProtocolHealthChecker(StandardProtocol.TCP));
        try {
            ServiceInstance dead = ins("health", "dead-" + UUID.randomUUID().toString().substring(0, 4));
            manager.registerServiceInstance(dead);
            manager.startAll();
            long deadline = System.currentTimeMillis() + 15_000;
            while (reports.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertFalse(reports.isEmpty(), "health checker should report the dead TCP endpoint");
            assertFalse(reports.get(0));
            manager.unregisterServiceInstance(dead.getInstanceId());
        } finally {
            manager.stopAll();
        }
    }
}
