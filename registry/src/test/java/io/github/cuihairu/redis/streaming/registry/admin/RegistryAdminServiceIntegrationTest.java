package io.github.cuihairu.redis.streaming.registry.admin;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@link RegistryAdminService}: service/instance details,
 * aggregated metrics, health snapshot and expired-instance cleanup.
 */
@Tag("integration")
class RegistryAdminServiceIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void serviceAndInstanceDetailsHealthAndCleanup() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String svc = "admin-svc-" + uid;
        RedisNamingService naming = new RedisNamingService(client);
        naming.start();
        try {
            ServiceInstance a = DefaultServiceInstance.builder()
                    .serviceName(svc).instanceId("a").host("10.2.0.1").port(9001)
                    .protocol(StandardProtocol.HTTP).healthy(true).weight(5)
                    .metadata(Map.of("region", "north"))
                    .build();
            naming.register(a);

            RegistryAdminService admin = new RegistryAdminService(client, new BaseRedisConfig());

            assertTrue(admin.getAllServices().contains(svc));

            ServiceDetails details = admin.getServiceDetails(svc);
            assertEquals(svc, details.getServiceName());
            assertEquals(1, details.getInstances().size());
            assertNotNull(details.getAggregatedMetrics());

            InstanceDetails instance = admin.getInstanceDetails(svc, "a");
            assertNotNull(instance);
            assertEquals("10.2.0.1", instance.getHost());
            assertEquals(9001, instance.getPort());

            assertNotNull(admin.getInstanceMetrics(svc, "a"));
            assertFalse(admin.getActiveInstances(svc, Duration.ofMinutes(2)).isEmpty());

            Map<String, Object> health = admin.getRegistryHealth();
            assertNotNull(health);

            // expired cleanup: 0s grace removes the (older) heartbeat, returns counts
            Map<String, Integer> removed = admin.cleanupExpiredInstances(Duration.ofSeconds(0));
            assertNotNull(removed);

            // unknown entities answer gracefully
            assertNotNull(admin.getServiceDetails("no-such-" + uid));
            assertNull(admin.getInstanceDetails(svc, "missing-instance"));
        } finally {
            naming.stop();
            client.shutdown();
        }
    }
}
