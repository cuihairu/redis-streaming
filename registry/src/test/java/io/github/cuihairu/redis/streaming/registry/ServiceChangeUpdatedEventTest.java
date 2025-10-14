package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceProvider;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ServiceChangeUpdatedEventTest {

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
    public void testUpdatedEventOnReRegistration() throws Exception {
        RedisServiceProvider provider = new RedisServiceProvider(redissonClient);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        provider.start();
        consumer.start();

        try {
            String svc = "event-service";
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName(svc)
                    .instanceId("id-1")
                    .host("127.0.0.1")
                    .port(8080)
                    .weight(1)
                    .build();

            CountDownLatch updatedLatch = new CountDownLatch(1);
            ServiceChangeListener l = (serviceName, action, instance, allInstances) -> {
                if (action == ServiceChangeAction.UPDATED) {
                    updatedLatch.countDown();
                }
            };
            consumer.subscribe(svc, l);

            // first register -> ADDED
            provider.register(inst);
            Thread.sleep(300);

            // re-register with changes -> UPDATED
            ServiceInstance updated = DefaultServiceInstance.builder()
                    .serviceName(svc)
                    .instanceId("id-1")
                    .host("127.0.0.1")
                    .port(8081)
                    .weight(5)
                    .build();
            provider.register(updated);

            assertTrue(updatedLatch.await(3, TimeUnit.SECONDS), "Should receive UPDATED event on re-registration");

        } finally {
            consumer.stop();
            provider.stop();
        }
    }

    @Test
    public void testUpdatedEventOnHeartbeatMetadataChange() throws Exception {
        ServiceProviderConfig cfg = new ServiceProviderConfig();
        HeartbeatConfig hb = new HeartbeatConfig();
        hb.setEnableMetadataChangeDetection(true);
        hb.setMetadataUpdateIntervalSeconds(0); // allow immediate updates

        RedisServiceProvider provider = new RedisServiceProvider(redissonClient, cfg, hb, null);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redissonClient);
        provider.start();
        consumer.start();

        try {
            String svc = "hb-event-service";
            Map<String, String> md = new HashMap<>();
            md.put("version", "1.0.0");

            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName(svc)
                    .instanceId("id-2")
                    .host("127.0.0.1")
                    .port(8080)
                    .metadata(md)
                    .build();

            CountDownLatch updatedLatch = new CountDownLatch(1);
            ServiceChangeListener l = (serviceName, action, instance, allInstances) -> {
                if (action == ServiceChangeAction.UPDATED) {
                    updatedLatch.countDown();
                }
            };
            consumer.subscribe(svc, l);

            provider.register(inst);
            Thread.sleep(1000); // Wait longer for registration to fully complete

            // mutate metadata and send heartbeat -> should trigger METADATA_UPDATE => UPDATED event
            inst.getMetadata().put("version", "1.0.1");

            // Create a new instance with updated metadata to ensure the change is detected
            ServiceInstance updatedInst = DefaultServiceInstance.builder()
                    .serviceName(inst.getServiceName())
                    .instanceId(inst.getInstanceId())
                    .host(inst.getHost())
                    .port(inst.getPort())
                    .metadata(inst.getMetadata())
                    .build();

            provider.sendHeartbeat(updatedInst);

            // Wait longer for the event
            assertTrue(updatedLatch.await(5, TimeUnit.SECONDS), "Should receive UPDATED event after heartbeat metadata change");

        } finally {
            consumer.stop();
            provider.stop();
        }
    }
}
