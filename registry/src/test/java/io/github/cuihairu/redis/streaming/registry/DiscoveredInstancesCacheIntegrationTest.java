package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-45 end-to-end on real Redis: two services reusing the same instanceId (the
 * default hostname case) must both stay cached, and a deregistered instance must
 * leave the cache on the next discovery. (The deterministic pre-fix discriminators
 * are the mock-based {@code RedisServiceConsumerDiscoveredCacheTest}.)
 */
@Tag("integration")
class DiscoveredInstancesCacheIntegrationTest {

    private RedissonClient redis;
    private RedisNamingService naming;
    private final List<String> services = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redis = Redisson.create(cfg);
        naming = new RedisNamingService(redis);
        naming.start();
    }

    @AfterEach
    void tearDown() {
        try {
            naming.stop();
        } catch (Exception ignore) {
        }
        for (String svc : services) {
            for (String key : redis.getKeys().getKeys(KeysScanOptions.defaults().pattern("*" + svc + "*"))) {
                redis.getKeys().delete(key);
            }
        }
        redis.getSet(new ServiceConsumerConfig().getRegistryKeys().getServicesIndexKey(), StringCodec.INSTANCE)
                .remove(services.toArray(new String[0]));
        redis.shutdown();
    }

    private String uniqueService() {
        String svc = "it-b45-" + UUID.randomUUID().toString().substring(0, 8);
        services.add(svc);
        return svc;
    }

    private static ServiceInstance ins(String svc, String id, int port) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("127.0.0.1").port(port)
                .protocol(StandardProtocol.TCP).healthy(true).build();
    }

    @Test
    void sharedInstanceIdSurvivesAcrossServicesAndDeregistrationEvicts() {
        String svcA = uniqueService();
        String svcB = uniqueService();
        ServiceInstance a = ins(svcA, "host-1", 8081);
        ServiceInstance b = ins(svcB, "host-1", 8082);
        naming.register(a);
        naming.register(b);

        ServiceConsumerConfig config = new ServiceConsumerConfig();
        config.setEnableHealthCheck(false);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redis, config);
        consumer.start();
        try {
            assertEquals(1, consumer.discover(svcA).size());
            assertEquals(1, consumer.discover(svcB).size());
            assertEquals(2, consumer.getDiscoveredInstanceCount(),
                    "two services sharing an instanceId must both stay cached");

            naming.deregister(a);
            assertEquals(0, consumer.discover(svcA).size());
            assertEquals(1, consumer.discover(svcB).size());
            assertEquals(1, consumer.getDiscoveredInstanceCount(),
                    "the deregistered instance must leave the cache after reconciliation");
            assertTrue(consumer.isInstanceHealthy("host-1"),
                    "the surviving svc-b instance answers for the bare id");
        } finally {
            consumer.stop();
            naming.deregister(a);
            naming.deregister(b);
        }
    }
}
