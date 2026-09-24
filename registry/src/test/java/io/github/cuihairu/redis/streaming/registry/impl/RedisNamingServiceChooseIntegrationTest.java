package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.ConsistentHashLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.RedisMetricsProvider;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.ScoredLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage for RedisNamingService chooser convenience methods and
 * unsubscribe/discoverByMetadata delegation.
 */
@Tag("integration")
class RedisNamingServiceChooseIntegrationTest {

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

    private static ServiceInstance ins(String svc, String id, Map<String, String> meta) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("127.0.0.1").port(1)
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
    void chooseHealthyInstanceWorksThroughLoadBalancers() {
        String svc = uniqueService();
        naming.register(ins(svc, "a", Map.of("region", "cn", "weight", "10")));
        naming.register(ins(svc, "b", Map.of("region", "us", "weight", "1")));

        LoadBalancer roundRobin = new io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRoundRobinLoadBalancer();
        ServiceInstance picked = naming.chooseHealthyInstance(svc, roundRobin, Map.of());
        assertNotNull(picked);
        assertEquals(svc, picked.getServiceName());

        ServiceInstance hashed = naming.chooseHealthyInstance(
                svc, new ConsistentHashLoadBalancer(), Map.of("hashKey", "order-42"));
        assertNotNull(hashed);

        ServiceInstance scored = naming.chooseHealthyInstance(svc,
                new ScoredLoadBalancer(null, new RedisMetricsProvider(redis, new ServiceConsumerConfig())),
                Map.of());
        assertNotNull(scored);

        ServiceInstance filtered = naming.chooseHealthyInstanceByFilters(svc,
                Map.of("region", "cn"), null, roundRobin, Map.of());
        assertEquals("a", filtered.getInstanceId());

        assertEquals(2, naming.getInstancesByFilters(svc, Map.of(), Map.of()).size());
        assertEquals(2, naming.getHealthyInstancesByFilters(svc, Map.of(), null).size());
        assertEquals(1, naming.discoverByMetadata(svc, Map.of("region", "us")).size());
        assertEquals(1, naming.discoverHealthyByMetadata(svc, Map.of("region", "cn")).size());
    }

    @Test
    void subscribeUnsubscribeRoundTrip() {
        String svc = uniqueService();
        List<ServiceInstance> seen = new ArrayList<>();
        io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener listener =
                (serviceName, action, instance, instances) -> {
                    if (instance != null) {
                        seen.add(instance);
                    }
                };
        naming.subscribe(svc, listener);
        naming.register(ins(svc, "a", Map.of("region", "cn")));

        long deadline = System.currentTimeMillis() + 10_000;
        while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(seen.isEmpty());
        naming.unsubscribe(svc, listener);
    }
}
