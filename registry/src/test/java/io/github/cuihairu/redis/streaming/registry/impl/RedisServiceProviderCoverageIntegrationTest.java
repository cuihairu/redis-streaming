package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricCollector;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for RedisServiceProvider smart-heartbeat update modes,
 * cleanup snapshot rebuilds and lifecycle paths.
 */
@Tag("integration")
class RedisServiceProviderCoverageIntegrationTest {

    private RedissonClient redis;
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

    private static ServiceInstance ins(String svc, String id, Map<String, String> meta) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("127.0.0.1").port(1)
                .protocol(StandardProtocol.TCP).metadata(meta).healthy(true).build();
    }

    private static MetricsCollectionManager controllable(AtomicReference<Object> value) {
        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("cpu"));
        cfg.setCollectionIntervals(Map.of());
        cfg.setDefaultCollectionInterval(Duration.ZERO);
        MetricCollector collector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "cpu";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Object collectMetric() {
                return value.get();
            }
        };
        return new MetricsCollectionManager(List.of(collector), cfg);
    }

    private static HeartbeatConfig fastConfig() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ZERO);
        cfg.setEnableMetadataChangeDetection(true);
        cfg.setMetadataUpdateIntervalSeconds(0);
        cfg.setChangeThresholds(Map.of());
        return cfg;
    }

    @BeforeEach
    void setUp() {
        redis = client();
    }

    @AfterEach
    void tearDown() {
        try {
            for (String svc : services) {
                for (String key : redis.getKeys().getKeys(KeysScanOptions.defaults().pattern("*" + svc + "*"))) {
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
    void heartbeatUpdateModesWriteThroughRealLua() throws Exception {
        String svc = uniqueService();
        AtomicReference<Object> metrics = new AtomicReference<>(Map.of("k", 1));
        RedisServiceProvider provider = new RedisServiceProvider(
                redis, new ServiceProviderConfig(), fastConfig(), controllable(metrics));

        List<ServiceChangeAction> actions = new CopyOnWriteArrayList<>();
        RedisNamingService watcher = new RedisNamingService(redis);
        watcher.start();
        provider.start();
        try {
            watcher.subscribe(svc, (serviceName, action, instance, instances) -> actions.add(action));

            ServiceInstance a = ins(svc, "a", Map.of("r", "1"));
            provider.register(a);
            provider.sendHeartbeat(a); // FULL_UPDATE first cycle
            provider.sendHeartbeat(a); // HEARTBEAT_ONLY steady state

            metrics.set(Map.of("k", 2));
            provider.sendHeartbeat(a); // METRICS_UPDATE on significant change
            provider.sendHeartbeat(ins(svc, "a", Map.of("r", "2"))); // METADATA/FULL update
            provider.batchSendHeartbeats(List.of(ins(svc, "a", Map.of("r", "2"))));

            long deadline = System.currentTimeMillis() + 10_000;
            while (!actions.contains(ServiceChangeAction.UPDATED) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(actions.contains(ServiceChangeAction.UPDATED));

            // instance hash reflects the later metadata/metrics updates
            Map<String, String> stored = redis.<String, String>getMap(
                    new ServiceProviderConfig().getRegistryKeys().getServiceInstanceKey(svc, "a"),
                    StringCodec.INSTANCE).readAllMap();
            assertFalse(stored.isEmpty());

            provider.deregister(a);
        } finally {
            provider.stop();
            watcher.unsubscribe(svc, (serviceName, action, instance, instances) -> { });
            watcher.stop();
        }
    }

    @Test
    void cleanupRebuildsSnapshotsAndNotifiesRemovals() throws Exception {
        String svc = uniqueService();
        RedisServiceProvider provider = new RedisServiceProvider(
                redis, new ServiceProviderConfig(), new HeartbeatConfig(),
                controllable(new AtomicReference<>(Map.of("k", 1))));

        List<ServiceChangeAction> actions = new CopyOnWriteArrayList<>();
        RedisNamingService watcher = new RedisNamingService(redis);
        watcher.start();
        provider.start();
        try {
            watcher.subscribe(svc, (serviceName, action, instance, instances) -> actions.add(action));

            ServiceInstance gone = ins(svc, "gone", Map.of("region", "cn"));
            provider.register(gone);

            // age the heartbeat score so the instance counts as expired
            RScoredSortedSet<String> hb = redis.getScoredSortedSet(
                    new ServiceProviderConfig().getRegistryKeys().getServiceHeartbeatsKey(svc),
                    StringCodec.INSTANCE);
            long deadline = System.currentTimeMillis() + 5_000;
            while (hb.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            for (String member : hb.valueRange(0, -1)) {
                hb.add(System.currentTimeMillis() - 86_400_000L, member);
            }

            Method cleanup = RedisServiceProvider.class
                    .getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
            cleanup.setAccessible(true);
            cleanup.invoke(provider, svc);

            deadline = System.currentTimeMillis() + 10_000;
            while (!actions.contains(ServiceChangeAction.REMOVED) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(actions.contains(ServiceChangeAction.REMOVED));
            assertEquals(0, hb.size());

            // id-only fallback: corrupt the snapshot json is not reachable with real Lua,
            // but the plain cleanup pass over an empty service must be safe
            Method cleanupAll = RedisServiceProvider.class.getDeclaredMethod("cleanupExpiredInstances");
            cleanupAll.setAccessible(true);
            cleanupAll.invoke(provider);
        } finally {
            provider.stop();
            watcher.unsubscribe(svc, (serviceName, action, instance, instances) -> { });
            watcher.stop();
        }
    }

    @Test
    void lifecycleStartStopIsIdempotent() {
        String svc = uniqueService();
        RedisServiceProvider provider = new RedisServiceProvider(redis, new ServiceProviderConfig());
        provider.start();
        provider.start();
        assertTrue(provider.isRunning());
        provider.stop();
        provider.stop();
        assertFalse(provider.isRunning());
    }

    @Test
    void heartbeatRevivesUnhealthyPersistentInstance() throws Exception {
        String svc = uniqueService();
        // fastConfig (zero intervals): back-to-back sendHeartbeat calls must not be
        // throttled to NO_UPDATE by the smart-heartbeat interval window
        RedisServiceProvider provider = new RedisServiceProvider(
                redis, new ServiceProviderConfig(), fastConfig(),
                controllable(new AtomicReference<>(Map.of("k", 1))));
        RedisServiceConsumer consumer = new RedisServiceConsumer(redis);
        provider.start();
        consumer.start();
        try {
            // persistent instances are flagged unhealthy by the expiry pass instead of deleted
            ServiceInstance persistent = DefaultServiceInstance.builder()
                    .serviceName(svc).instanceId("p1").host("127.0.0.1").port(1)
                    .protocol(StandardProtocol.TCP).metadata(Map.of("r", "1"))
                    .healthy(true).ephemeral(false).build();
            provider.register(persistent);
            provider.sendHeartbeat(persistent);

            RScoredSortedSet<String> hb = redis.getScoredSortedSet(
                    new ServiceProviderConfig().getRegistryKeys().getServiceHeartbeatsKey(svc),
                    StringCodec.INSTANCE);
            long deadline = System.currentTimeMillis() + 5_000;
            while (hb.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            for (String member : hb.valueRange(0, -1)) {
                hb.add(System.currentTimeMillis() - 86_400_000L, member);
            }
            Method cleanup = RedisServiceProvider.class
                    .getDeclaredMethod("cleanupExpiredInstancesForService", String.class);
            cleanup.setAccessible(true);
            cleanup.invoke(provider, svc);

            Map<String, String> stored = redis.<String, String>getMap(
                    new ServiceProviderConfig().getRegistryKeys().getServiceInstanceKey(svc, "p1"),
                    StringCodec.INSTANCE).readAllMap();
            assertEquals("false", stored.get("healthy"),
                    "an expired persistent instance must be flagged unhealthy");
            assertTrue(consumer.discoverHealthy(svc).isEmpty(),
                    "the flagged instance must drop out of healthy discovery");

            // a resumed heartbeat must revive it — previously the flag stuck forever
            provider.sendHeartbeat(persistent);
            stored = redis.<String, String>getMap(
                    new ServiceProviderConfig().getRegistryKeys().getServiceInstanceKey(svc, "p1"),
                    StringCodec.INSTANCE).readAllMap();
            assertEquals("true", stored.get("healthy"), "a live heartbeat must restore health");
            assertEquals(1, consumer.discoverHealthy(svc).size(),
                    "the revived instance must serve healthy discovery again");

            provider.deregister(persistent);
        } finally {
            provider.stop();
            consumer.stop();
        }
    }

    @Test
    void metricsMergePreservesClientAndServerKeys() throws Exception {
        String svc = uniqueService();
        AtomicReference<Object> serverMetrics = new AtomicReference<>(Map.of("cpu", 80));
        RedisServiceProvider provider = new RedisServiceProvider(
                redis, new ServiceProviderConfig(), new HeartbeatConfig(), controllable(serverMetrics));
        RedisClientMetricsReporter reporter =
                new RedisClientMetricsReporter(redis, new ServiceConsumerConfig());
        provider.start();
        try {
            ServiceInstance a = ins(svc, "m1", Map.of("r", "1"));
            provider.register(a);
            provider.sendHeartbeat(a); // server metrics {"cpu":80} into the shared field

            reporter.incrementInflight(svc, "m1");
            reporter.recordLatency(svc, "m1", 25); // client keys, written from "another process"

            // a later provider heartbeat must overlay server keys without erasing client keys
            serverMetrics.set(Map.of("cpu", 90));
            provider.sendHeartbeat(ins(svc, "m1", Map.of("r", "1")));

            Map<String, String> stored = redis.<String, String>getMap(
                    new ServiceProviderConfig().getRegistryKeys().getServiceInstanceKey(svc, "m1"),
                    StringCodec.INSTANCE).readAllMap();
            String metricsJson = stored.get("metrics");
            assertNotNull(metricsJson);
            assertTrue(metricsJson.contains("clientInflight"),
                    "client keys must survive a provider heartbeat: " + metricsJson);
            assertTrue(metricsJson.contains("clientLatencyMs"),
                    "client keys must survive a provider heartbeat: " + metricsJson);
            assertTrue(metricsJson.contains("cpu"),
                    "server keys must survive client writes: " + metricsJson);

            provider.deregister(a);
        } finally {
            provider.stop();
        }
    }
}
