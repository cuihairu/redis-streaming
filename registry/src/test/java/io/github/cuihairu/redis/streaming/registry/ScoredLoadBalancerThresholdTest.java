package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.*;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ScoredLoadBalancerThresholdTest {
    private static RedissonClient redissonClient;

    @BeforeAll
    public static void setupRedis() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    public static void teardownRedis() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @BeforeEach
    public void flush() {
        redissonClient.getKeys().flushdb();
    }

    @Test
    public void testHardConstraintsExcludeBadInstances() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        provider.start();
        consumer.start();
        try {
            String svc = "threshold-svc";
            Map<String,String> md = new HashMap<>(); md.put("weight","5");
            ServiceInstance good = DefaultServiceInstance.builder().serviceName(svc).instanceId("good")
                    .host("127.0.0.1").port(8080).metadata(md).build();
            ServiceInstance bad = DefaultServiceInstance.builder().serviceName(svc).instanceId("bad")
                    .host("127.0.0.2").port(8081).metadata(md).build();
            provider.register(good);
            provider.register(bad);

            String k1 = consumer.getConfig().getServiceInstanceKey(svc, "good");
            String k2 = consumer.getConfig().getServiceInstanceKey(svc, "bad");
            RMap<String,String> m1 = redissonClient.getMap(k1, StringCodec.INSTANCE);
            RMap<String,String> m2 = redissonClient.getMap(k2, StringCodec.INSTANCE);
            m1.put("metrics", "{\"cpu\":40,\"latency\":80,\"memory\":50,\"inflight\":2,\"queue\":5,\"errorRate\":1}");
            m2.put("metrics", "{\"cpu\":95,\"latency\":500,\"memory\":95,\"inflight\":100,\"queue\":500,\"errorRate\":50}");

            List<ServiceInstance> all = consumer.discoverHealthy(svc);
            LoadBalancerConfig cfg = new LoadBalancerConfig();
            cfg.setMaxCpuPercent(80);
            cfg.setMaxLatencyMs(200);
            cfg.setMaxMemoryPercent(90);
            cfg.setMaxInflight(50);
            cfg.setMaxQueue(50);
            cfg.setMaxErrorRatePercent(10);

            MetricsProvider mp = new RedisMetricsProvider(redissonClient, consumer.getConfig());
            LoadBalancer lb = new ScoredLoadBalancer(cfg, mp);

            ServiceInstance chosen = lb.choose(svc, all, Map.of());
            assertNotNull(chosen);
            assertEquals("good", chosen.getInstanceId());
        } finally {
            consumer.stop();
            provider.stop();
        }
    }
}
