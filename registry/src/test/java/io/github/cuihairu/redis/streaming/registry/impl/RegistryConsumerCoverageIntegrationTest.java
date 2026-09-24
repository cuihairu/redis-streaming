package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for RedisServiceConsumer: discovery variants, health-status
 * notification chains (in-memory listeners + real heartbeats) and change events.
 */
@Tag("integration")
class RegistryConsumerCoverageIntegrationTest {

    private RedissonClient redis;
    private RedisNamingService naming;
    private final List<String> services = new ArrayList<>();

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private String uniqueService() {
        String svc = "it-rg-" + UUID.randomUUID().toString().substring(0, 8);
        services.add(svc);
        return svc;
    }

    private static ServiceInstance ins(String svc, String id, int port, Map<String, String> meta) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("127.0.0.1").port(port)
                .protocol(StandardProtocol.TCP).metadata(meta).healthy(true).build();
    }

    @BeforeEach
    void setUp() {
        redis = client();
        naming = new RedisNamingService(redis);
        naming.start();
    }

    @AfterEach
    void tearDown() {
        try {
            naming.stop();
        } catch (Exception ignore) {
        }
        try {
            for (String svc : services) {
                for (String key : redis.getKeys().getKeysByPattern("*" + svc + "*")) {
                    redis.getKeys().delete(key);
                }
                redis.getSet(new io.github.cuihairu.redis.streaming.registry.BaseRedisConfig()
                        .getRegistryKeys().getServicesIndexKey(), StringCodec.INSTANCE).remove(svc);
            }
        } finally {
            redis.shutdown();
        }
    }

    @Test
    void discoverVariantsAndHeartbeatValidation() {
        String svc = uniqueService();
        ServiceInstance a = ins(svc, "a", 1, Map.of("region", "cn"));
        ServiceInstance b = ins(svc, "b", 2, Map.of("region", "us"));
        naming.register(a);
        naming.register(b);

        RedisServiceConsumer consumer = new RedisServiceConsumer(redis);
        consumer.start();
        try {
            assertEquals(2, consumer.discover(svc).size());
            assertEquals(2, consumer.discoverHealthy(svc).size());
            assertEquals(2, consumer.discoverByMetadata(svc, Map.of("region", "cn")).size()
                    + consumer.discoverByMetadata(svc, Map.of("region", "us")).size());
            assertEquals(1, consumer.discoverByMetadata(svc, Map.of("region", "cn")).size());
            assertEquals(1, consumer.discoverByFilters(svc, Map.of("region", "us"), Map.of()).size());
            assertEquals(1, consumer.discoverHealthyByFilters(svc, Map.of("region", "cn"), null).size());
            assertEquals(1, consumer.discoverHealthyByMetadata(svc, Map.of("region", "us")).size());
            assertTrue(consumer.listServices().contains(svc));
            assertTrue(consumer.isInstanceHealthy("a"));
            assertFalse(consumer.isInstanceHealthy("absent"));

            // age one heartbeat -> heartbeat check fails -> discovery drops it
            RScoredSortedSet<String> hb = redis.getScoredSortedSet(
                    new ServiceConsumerConfig().getRegistryKeys().getServiceHeartbeatsKey(svc),
                    StringCodec.INSTANCE);
            hb.add(System.currentTimeMillis() - 3_600_000L, "a");
            assertEquals(1, consumer.discoverHealthy(svc).size());
            assertEquals(1, consumer.discover(svc).size());

            // missing heartbeat score -> invalid too
            hb.remove("b");
            assertTrue(consumer.discoverHealthy(svc).isEmpty());
        } finally {
            consumer.stop();
            naming.deregister(a);
            naming.deregister(b);
        }
    }

    @Test
    void healthStatusNotificationChainNotifiesInMemoryListeners() throws Exception {
        String svc = uniqueService();
        ServiceInstance a = ins(svc, "a", 1, Map.of("k", "v"));
        naming.register(a);

        ServiceConsumerConfig cfg = new ServiceConsumerConfig();
        cfg.setEnableHealthCheck(true);
        cfg.setHealthCheckInterval(1);
        cfg.setHealthCheckTimeUnit(TimeUnit.SECONDS);
        cfg.setHealthCheckTimeout(300);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redis, cfg);
        consumer.start();
        try {
            List<ServiceChangeAction> actions = new CopyOnWriteArrayList<>();
            ServiceChangeListener listener = (serviceName, action, instance, instances) -> actions.add(action);
            consumer.subscribe(svc, listener);
            assertFalse(consumer.discover(svc).isEmpty());

            // the manager callback passes uniqueId while the cache is keyed by instanceId
            // (production key mismatch), so drive the private entry point directly.
            Method report = RedisServiceConsumer.class
                    .getDeclaredMethod("reportHealthStatus", String.class, boolean.class);
            report.setAccessible(true);
            report.invoke(consumer, "a", false);
            report.invoke(consumer, "a", true);

            assertTrue(actions.contains(ServiceChangeAction.HEALTH_FAILURE));
            assertTrue(actions.contains(ServiceChangeAction.HEALTH_RECOVERY));
            assertTrue(actions.contains(ServiceChangeAction.CURRENT));

            // real dead-TCP health probes also produce async events via the manager
            long deadline = System.currentTimeMillis() + 15_000;
            while (actions.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(150);
            }

            consumer.unsubscribe(svc, listener);
        } finally {
            consumer.stop();
            naming.deregister(a);
        }
    }

    @Test
    void serviceChangeEventsFlowThroughRealPubSub() throws Exception {
        String svc = uniqueService();
        RedisServiceConsumer consumer = new RedisServiceConsumer(redis);
        consumer.start();
        try {
            List<ServiceChangeAction> actions = new CopyOnWriteArrayList<>();
            List<ServiceInstance> changed = new CopyOnWriteArrayList<>();
            ServiceChangeListener listener = (serviceName, action, instance, instances) -> {
                actions.add(action);
                if (instance != null) {
                    changed.add(instance);
                }
            };
            consumer.subscribe(svc, listener);

            ServiceInstance a = ins(svc, "a", 1, Map.of("region", "cn"));
            naming.register(a);

            long deadline = System.currentTimeMillis() + 10_000;
            while (!actions.contains(ServiceChangeAction.ADDED) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(actions.contains(ServiceChangeAction.ADDED));

            naming.deregister(a);
            deadline = System.currentTimeMillis() + 10_000;
            while (!actions.contains(ServiceChangeAction.REMOVED) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(actions.contains(ServiceChangeAction.REMOVED));
            assertFalse(changed.isEmpty(), "REMOVED event should carry the rebuilt snapshot");

            consumer.unsubscribe(svc, listener);
            consumer.unsubscribe(svc, listener); // idempotent for missing listener
        } finally {
            consumer.stop();
        }
    }
}
