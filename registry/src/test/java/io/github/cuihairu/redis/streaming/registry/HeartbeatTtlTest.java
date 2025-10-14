package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for sliding TTL on instance hash.
 * Ensures ephemeral instances refresh TTL on heartbeat and persistent instances do not use TTL.
 */
@Tag("integration")
public class HeartbeatTtlTest {

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
    public void testEphemeralInstanceTtlRefreshesOnHeartbeat() throws Exception {
        ServiceProviderConfig cfg = new ServiceProviderConfig();
        cfg.setHeartbeatTimeoutSeconds(2); // short TTL for test

        RedisServiceProvider provider = new RedisServiceProvider(redissonClient, cfg);
        provider.start();

        try {
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName("ttl-service")
                    .instanceId("ephemeral-1")
                    .host("127.0.0.1")
                    .port(8080)
                    .ephemeral(true)
                    .metadata(new HashMap<>())
                    .build();

            provider.register(inst);

            String instanceKey = cfg.getRegistryKeys().getServiceInstanceKey("ttl-service", "ephemeral-1");
            RKeys keys = redissonClient.getKeys();

            Long ttl1 = keys.remainTimeToLive(instanceKey);
            assertNotNull(ttl1);
            assertTrue(ttl1 > 0, "Ephemeral instance should have positive TTL after registration");

            // Sleep less than timeout and send heartbeat to refresh TTL
            Thread.sleep(1500);
            provider.sendHeartbeat(inst);
            Thread.sleep(300); // let script run

            Long ttl2 = keys.remainTimeToLive(instanceKey);
            assertNotNull(ttl2);
            assertTrue(ttl2 > 0, "TTL should be refreshed on heartbeat for ephemeral instance");
            assertTrue(ttl2 > 500, "TTL should be extended (rough check)");

        } finally {
            provider.stop();
        }
    }

    @Test
    public void testPersistentInstanceHasNoTtl() throws Exception {
        ServiceProviderConfig cfg = new ServiceProviderConfig();
        cfg.setHeartbeatTimeoutSeconds(2); // even if set, persistent should not use TTL

        RedisServiceProvider provider = new RedisServiceProvider(redissonClient, cfg);
        provider.start();

        try {
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName("ttl-service")
                    .instanceId("persistent-1")
                    .host("127.0.0.1")
                    .port(8080)
                    .ephemeral(false)
                    .build();

            provider.register(inst);

            String instanceKey = cfg.getRegistryKeys().getServiceInstanceKey("ttl-service", "persistent-1");
            RKeys keys = redissonClient.getKeys();
            Long ttl = keys.remainTimeToLive(instanceKey);

            // -1 means no expire; some Redis clients may return null, handle both
            if (ttl != null) {
                assertTrue(ttl == -1 || ttl > 1000000000L, "Persistent instance should not have a TTL");
            }
        } finally {
            provider.stop();
        }
    }
}

