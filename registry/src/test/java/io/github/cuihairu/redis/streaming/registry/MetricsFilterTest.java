package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
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
public class MetricsFilterTest {
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
    public void testDiscoverByMetricsFilter() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        provider.start();
        consumer.start();

        try {
            // Register two instances
            ServiceInstance i1 = DefaultServiceInstance.builder()
                    .serviceName("mfilter-service")
                    .instanceId("i-1")
                    .host("127.0.0.1")
                    .port(8080)
                    .build();
            ServiceInstance i2 = DefaultServiceInstance.builder()
                    .serviceName("mfilter-service")
                    .instanceId("i-2")
                    .host("127.0.0.2")
                    .port(8081)
                    .build();
            provider.register(i1);
            provider.register(i2);

            // Manually set metrics JSON on instance hash to simulate metrics presence
            String key1 = provider.getRegistryKeys().getServiceInstanceKey("mfilter-service", "i-1");
            String key2 = provider.getRegistryKeys().getServiceInstanceKey("mfilter-service", "i-2");
            RMap<String, String> m1 = redissonClient.getMap(key1, StringCodec.INSTANCE);
            RMap<String, String> m2 = redissonClient.getMap(key2, StringCodec.INSTANCE);
            m1.put("metrics", "{\"cpu\":50,\"latency\":30}");
            m2.put("metrics", "{\"cpu\":80,\"latency\":70}");

            // Filter: cpu < 60 AND latency <= 50
            Map<String, String> metricsFilters = new HashMap<>();
            metricsFilters.put("cpu:<", "60");
            metricsFilters.put("latency:<=", "50");

            List<ServiceInstance> matched = consumer.discoverByFilters("mfilter-service", null, metricsFilters);
            assertEquals(1, matched.size());
            assertEquals("i-1", matched.get(0).getInstanceId());

            // Healthy variant uses same result (both are healthy by default)
            List<ServiceInstance> matchedHealthy = consumer.discoverHealthyByFilters("mfilter-service", null, metricsFilters);
            assertEquals(1, matchedHealthy.size());
            assertEquals("i-1", matchedHealthy.get(0).getInstanceId());

        } finally {
            consumer.stop();
            provider.stop();
        }
    }
}

