package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.client.ClientSelector;
import io.github.cuihairu.redis.streaming.registry.client.ClientSelectorConfig;
import io.github.cuihairu.redis.streaming.registry.filter.FilterBuilder;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ClientSelectorIntegrationTest {
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
    public void testSelectorWithFallbackAndScoredLB() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        RedisNamingService naming = new RedisNamingService(redissonClient);
        provider.start();
        consumer.start();
        naming.start();
        try {
            String svc = "selector-svc";
            Map<String,String> md1 = new HashMap<>(); md1.put("region","us-east-1"); md1.put("weight","5");
            Map<String,String> md2 = new HashMap<>(); md2.put("region","us-east-1"); md2.put("weight","5");

            ServiceInstance i1 = DefaultServiceInstance.builder().serviceName(svc).instanceId("i1")
                    .host("127.0.0.1").port(8080).metadata(md1).build();
            ServiceInstance i2 = DefaultServiceInstance.builder().serviceName(svc).instanceId("i2")
                    .host("127.0.0.2").port(8081).metadata(md2).build();
            provider.register(i1);
            provider.register(i2);

            // only i1 has good metrics
            String k1 = consumer.getConfig().getServiceInstanceKey(svc, "i1");
            String k2 = consumer.getConfig().getServiceInstanceKey(svc, "i2");
            RMap<String,String> m1 = redissonClient.getMap(k1, StringCodec.INSTANCE);
            RMap<String,String> m2 = redissonClient.getMap(k2, StringCodec.INSTANCE);
            m1.put("metrics", "{\"cpu\":30,\"latency\":40}");
            m2.put("metrics", "{\"cpu\":90,\"latency\":120}");

            var fbMd = FilterBuilder.create().metaEq("region","us-east-1").buildMetadata();
            var fbMt = FilterBuilder.create().metricLt("cpu", 80).metricLte("latency", 60).buildMetrics();

            LoadBalancerConfig cfg = new LoadBalancerConfig();
            cfg.setPreferredRegion("us-east-1");
            MetricsProvider mp = new RedisMetricsProvider(redissonClient, consumer.getConfig());
            LoadBalancer lb = new ScoredLoadBalancer(cfg, mp);

            ClientSelector selector = new ClientSelector(naming, new ClientSelectorConfig());
            ServiceInstance chosen = selector.select(svc, fbMd, fbMt, lb, Map.of());
            assertNotNull(chosen);
            assertEquals("i1", chosen.getInstanceId());
        } finally {
            naming.stop();
            consumer.stop();
            provider.stop();
        }
    }
}
