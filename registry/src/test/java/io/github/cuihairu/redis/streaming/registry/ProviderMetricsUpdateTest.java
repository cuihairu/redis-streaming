package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ProviderMetricsUpdateTest {
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
    public void testMetricsFieldPresentAfterHeartbeat() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        provider.start();
        consumer.start();
        try {
            String svc = "metrics-svc";
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName(svc)
                    .instanceId("m1")
                    .host("127.0.0.1")
                    .port(8080)
                    .ephemeral(true)
                    .build();
            provider.register(inst);
            Thread.sleep(300);

            // trigger heartbeat -> provider has default metrics manager which will collect
            provider.sendHeartbeat(inst);
            Thread.sleep(300);

            String instanceKey = consumer.getConfig().getServiceInstanceKey(svc, "m1");
            RMap<String,String> map = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
            String metricsJson = map.get("metrics");
            assertNotNull(metricsJson);
            assertTrue(metricsJson.startsWith("{") && metricsJson.length() > 2);

            List<ServiceInstance> healthy = consumer.discoverHealthy(svc);
            assertEquals(1, healthy.size());
        } finally {
            consumer.stop();
            provider.stop();
        }
    }
}

