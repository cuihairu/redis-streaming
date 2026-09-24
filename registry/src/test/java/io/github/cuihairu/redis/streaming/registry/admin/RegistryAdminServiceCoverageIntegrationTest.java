package io.github.cuihairu.redis.streaming.registry.admin;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for RegistryAdminService: active-instance collection,
 * details/metrics/health aggregation and manual expired cleanup.
 */
@Tag("integration")
class RegistryAdminServiceCoverageIntegrationTest {

    private RedissonClient redis;
    private RedisServiceProvider provider;
    private RegistryAdminService admin;
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

    @BeforeEach
    void setUp() {
        redis = client();
        provider = new RedisServiceProvider(redis, new ServiceProviderConfig());
        provider.start();
        admin = new RegistryAdminService(redis, new BaseRedisConfig());
    }

    @AfterEach
    void tearDown() {
        try {
            provider.stop();
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
    void adminExposesInstancesMetricsAndHealth() {
        String svc = uniqueService();
        ServiceInstance a = ins(svc, "a", Map.of("region", "cn"));
        ServiceInstance b = ins(svc, "b", Map.of("region", "us"));
        provider.register(a);
        provider.register(b);
        provider.sendHeartbeat(a);
        provider.sendHeartbeat(b);

        assertTrue(admin.getAllServices().contains(svc));

        List<InstanceDetails> active = admin.getActiveInstances(svc, Duration.ofMinutes(2));
        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(d -> d.getLastHeartbeatTime() > 0));

        ServiceDetails details = admin.getServiceDetails(svc, Duration.ofMinutes(2));
        assertEquals(2, details.getInstances().size());
        Map<String, Object> aggregated = details.getAggregatedMetrics();
        assertNotNull(aggregated);
        assertTrue(aggregated.containsKey("avgHeartbeatDelay"));
        assertNotNull(admin.getServiceDetails(svc));

        Map<String, Object> metrics = admin.getInstanceMetrics(svc, "a");
        assertNotNull(metrics);
        assertTrue(admin.getInstanceMetrics(svc, "absent").isEmpty());

        Map<String, Object> health = admin.getRegistryHealth();
        assertTrue((Integer) health.get("totalServices") >= 1);
        assertTrue((Integer) health.get("totalInstances") >= 2);
        assertTrue((Double) health.get("healthyRate") >= 0);

        // corrupt one instance hash -> parseInstanceDetails fallbacks
        RMap<String, String> map = redis.getMap(
                new BaseRedisConfig().getRegistryKeys().getServiceInstanceKey(svc, "b"),
                StringCodec.INSTANCE);
        map.put("metadata", "{{bad-json");
        map.put("metrics", "{{bad-json");
        map.put("port", "not-a-number");
        InstanceDetails dirty = admin.getInstanceDetails(svc, "b");
        assertNotNull(dirty);
        assertEquals(0, dirty.getPort());
        assertTrue(dirty.getMetadata().isEmpty());

        provider.deregister(a);
        provider.deregister(b);
    }

    @Test
    void cleanupExpiredInstancesRemovesStaleRows() throws Exception {
        String svc = uniqueService();
        ServiceInstance a = ins(svc, "a", Map.of("region", "cn"));
        provider.register(a);

        RScoredSortedSet<String> hb = redis.getScoredSortedSet(
                new BaseRedisConfig().getRegistryKeys().getServiceHeartbeatsKey(svc),
                StringCodec.INSTANCE);
        long deadline = System.currentTimeMillis() + 5_000;
        while (hb.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        for (String member : hb.valueRange(0, -1)) {
            hb.add(System.currentTimeMillis() - 86_400_000L, member);
        }

        Map<String, Integer> result = admin.cleanupExpiredInstances(Duration.ofMinutes(1));
        assertTrue(result.getOrDefault(svc, 0) >= 1);

        deadline = System.currentTimeMillis() + 5_000;
        while (admin.getActiveInstances(svc, Duration.ofMinutes(1)).size() > 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(admin.getActiveInstances(svc, Duration.ofMinutes(1)).isEmpty());
    }
}
