package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end lifecycle coverage for the Redis naming service: register/heartbeat/deregister,
 * metadata-filtered discovery, and change notifications.
 */
@Tag("integration")
class RedisNamingServiceLifecycleIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static ServiceInstance instance(String service, String id, String host, int port,
                                             Map<String, String> metadata) {
        return DefaultServiceInstance.builder()
                .serviceName(service)
                .instanceId(id)
                .host(host)
                .port(port)
                .protocol(StandardProtocol.HTTP)
                .metadata(metadata)
                .healthy(true)
                .weight(10)
                .build();
    }

    private static <T extends java.util.Collection<?>> T pollUntil(java.util.function.Supplier<T> supplier,
                                                                    java.util.function.Predicate<T> condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        T last = null;
        while (System.currentTimeMillis() < deadline) {
            last = supplier.get();
            if (condition.test(last)) {
                return last;
            }
            Thread.sleep(200);
        }
        fail("condition not met; last value size=" + (last == null ? -1 : last.size()));
        return last;
    }

    @Test
    void registerHeartbeatDiscoverAndDeregister() throws Exception {
        RedissonClient client = createClient();
        String svc = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
        RedisNamingService naming = new RedisNamingService(client);
        naming.start();
        try {
            ServiceInstance a = instance(svc, "a", "10.0.0.1", 8081, Map.of("region", "east", "version", "2.0"));
            ServiceInstance b = instance(svc, "b", "10.0.0.2", 8082, Map.of("region", "west", "version", "1.0"));

            naming.register(a);
            naming.register(b);
            naming.register(a);                 // re-register is fine
            naming.heartbeat(a);
            naming.batchHeartbeat(List.of(a, b));

            List<ServiceInstance> all = naming.getInstances(svc, true);
            assertEquals(2, all.size());
            assertEquals(2, naming.getHealthyInstances(svc).size());
            assertEquals(2, naming.discoverHealthy(svc).size());
            assertTrue(naming.discover(svc).size() >= 2);

            Thread.sleep(1500); // let heartbeat/index settle before filtered queries
            // metadata filters (key=field[:op], value=operand; metadata fields only)
            List<ServiceInstance> east = naming.getHealthyInstancesByMetadata(svc, Map.of("region", "east"));
            assertEquals(1, east.size());
            assertEquals("a", east.get(0).getInstanceId());
            assertEquals(1, naming.getInstancesByMetadata(svc, Map.of("region:!=", "east")).size());
            assertEquals(1, naming.getHealthyInstancesByMetadata(svc, Map.of("version:<", "2.0")).size());
            assertEquals(1, naming.getHealthyInstancesByMetadata(svc, Map.of("version:>=", "2.0")).size());
            assertTrue(naming.discoverHealthyByMetadata(svc + "-none", Map.of("region", "x")).isEmpty());

            // deregister + REMOVED notification
            CountDownLatch removed = new CountDownLatch(1);
            AtomicReference<ServiceChangeAction> action = new AtomicReference<>();
            naming.subscribe(svc, (serviceName, act, changed, instances) -> {
                if (act == ServiceChangeAction.REMOVED
                        || (instances != null && instances.stream().noneMatch(i -> "b".equals(i.getInstanceId())))) {
                    action.set(act);
                    removed.countDown();
                }
            });
            naming.deregister(b);
            assertTrue(removed.await(10, TimeUnit.SECONDS), "subscriber should see deregistration");
            assertTrue(action.get() == ServiceChangeAction.REMOVED || action.get() == ServiceChangeAction.CURRENT,
                    "unexpected action " + action.get());

            pollUntil(() -> naming.getInstances(svc, true), l -> l.size() == 1);
            naming.deregister(b);               // idempotent
            naming.stop();
            assertFalse(naming.isRunning());
            naming.stop();                       // idempotent
        } finally {
            naming.stop();
            client.shutdown();
        }
    }

    @Test
    void serviceConsumerDirectApi() throws Exception {
        RedissonClient client = createClient();
        String svc = "cons-" + UUID.randomUUID().toString().substring(0, 8);
        RedisNamingService naming = new RedisNamingService(client);
        naming.start();
        RedisServiceConsumer consumer = new RedisServiceConsumer(client, new ServiceConsumerConfig());
        consumer.start();
        try {
            ServiceInstance c1 = instance(svc, "c1", "10.1.1.1", 80, Map.of("zone", "z1"));
            naming.register(c1);
            assertEquals(1, consumer.getHealthyInstances(svc).size());

            CountDownLatch gone = new CountDownLatch(1);
            consumer.subscribe(svc, (serviceName, act, changed, instances) -> {
                if (act == ServiceChangeAction.REMOVED) {
                    gone.countDown();
                }
            });
            naming.deregister(c1);
            assertTrue(gone.await(10, TimeUnit.SECONDS), "consumer listener should observe removal");
            assertTrue(consumer.getHealthyInstances(svc).isEmpty());
            assertTrue(consumer.discover(svc + "-missing").isEmpty());
            CopyOnWriteArrayList<ServiceInstance> polled = new CopyOnWriteArrayList<>(consumer.getAllInstances(svc));
            // getAllInstances may return empty after removal; just ensure no throw
            assertNotNull(polled);
            consumer.unsubscribe(svc, (n, i, x, y) -> { });
            consumer.stop();
            assertFalse(consumer.isRunning());
        } finally {
            consumer.stop();
            naming.stop();
            client.shutdown();
        }
    }
}
