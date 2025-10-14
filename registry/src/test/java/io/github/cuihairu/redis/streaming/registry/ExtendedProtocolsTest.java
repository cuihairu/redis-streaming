package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ExtendedProtocolsTest {
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
    public void testWsAndGrpcsProtocols() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        provider.start();
        consumer.start();

        try {
            ServiceInstance wsInst = DefaultServiceInstance.builder()
                    .serviceName("proto-service")
                    .instanceId("ws-1")
                    .host("127.0.0.1")
                    .port(80)
                    .protocol(StandardProtocol.WS)
                    .build();

            ServiceInstance grpcsInst = DefaultServiceInstance.builder()
                    .serviceName("proto-service")
                    .instanceId("grpcs-1")
                    .host("127.0.0.2")
                    .port(443)
                    .protocol(StandardProtocol.GRPCS)
                    .build();

            provider.register(wsInst);
            provider.register(grpcsInst);

            Thread.sleep(400);
            List<ServiceInstance> list = consumer.discover("proto-service");
            assertEquals(2, list.size());

            boolean hasWs = list.stream().anyMatch(i -> i.getProtocol() == StandardProtocol.WS);
            boolean hasGrpcs = list.stream().anyMatch(i -> i.getProtocol() == StandardProtocol.GRPCS);
            assertTrue(hasWs && hasGrpcs, "Should parse WS and GRPCS protocols correctly");
        } finally {
            consumer.stop();
            provider.stop();
        }
    }
}

