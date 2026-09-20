package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Cache refresh, live query and health-status reporting paths of RedisServiceConsumer. */
@Tag("integration")
class ConsumerCacheHealthIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void cacheRefreshLiveQueriesAndHealthReporting() throws Exception {
        RedissonClient redis = client();
        String svc = "cache-" + UUID.randomUUID().toString().substring(0, 8);
        RedisNamingService naming = new RedisNamingService(redis);
        naming.start();
        ServiceConsumerConfig ccfg = new ServiceConsumerConfig();
        ccfg.setEnableHealthCheck(true);
        ccfg.setHealthCheckInterval(1);
        ccfg.setHealthCheckTimeUnit(TimeUnit.SECONDS);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redis, ccfg);
        consumer.start();
        try {
            ServiceInstance a = DefaultServiceInstance.builder()
                    .serviceName(svc).instanceId("a").host("127.0.0.1").port(1)
                    .protocol(StandardProtocol.TCP).metadata(Map.of("r", "1")).healthy(true).build();
            naming.register(a);

            // repeated fetches exercise cache priming + expiry refresh
            assertFalse(consumer.getInstances(svc, true).isEmpty());
            Thread.sleep(1200);
            assertFalse(consumer.getInstances(svc, false).isEmpty());
            assertFalse(consumer.getAllInstances(svc).isEmpty());
            assertFalse(consumer.getHealthyInstances(svc).isEmpty());

            CopyOnWriteArrayList<Boolean> healthEvents = new CopyOnWriteArrayList<>();
            consumer.subscribe(svc, (serviceName, action, instance, instances) -> healthEvents.add(Boolean.TRUE));

            long deadline = System.currentTimeMillis() + 15_000;
            while (healthEvents.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(150);
            }
            assertFalse(healthEvents.isEmpty(), "health-checker should emit events for the dead TCP endpoint");

            consumer.unsubscribe(svc, (serviceName, action, instance, instances) -> { });
            naming.deregister(a);
        } finally {
            consumer.stop();
            naming.stop();
            redis.shutdown();
        }
    }
}
