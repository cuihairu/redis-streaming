package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.admin.RegistryAdminService;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminKeyConsistencyTest {
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
    public void testAdminSeesRegisteredServices() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        provider.start();
        try {
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName("admin-service")
                    .instanceId("a-1")
                    .host("127.0.0.1")
                    .port(8080)
                    .build();
            provider.register(inst);

            // Wait a bit for the registration to be fully processed
            Thread.sleep(500);

            RegistryAdminService admin = new RegistryAdminService(redissonClient, new BaseRedisConfig());
            Set<String> services = admin.getAllServices();
            assertTrue(services.contains("admin-service"));

            var details = admin.getServiceDetails("admin-service");
            assertNotNull(details);
            assertEquals("admin-service", details.getServiceName());
            assertNotNull(details.getInstances());
            assertTrue(details.getInstances().size() >= 1);
        } finally {
            provider.stop();
        }
    }
}

