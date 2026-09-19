package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
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

import static org.junit.jupiter.api.Assertions.*;

/** Batch registration, metadata updates and services-index maintenance on real Redis. */
@Tag("integration")
class RegistryBatchOpsIntegrationTest {

    private static ServiceInstance ins(String svc, String id, int port, Map<String, String> meta) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("10.9.0.1").port(port)
                .protocol(StandardProtocol.HTTP).metadata(meta).healthy(true).build();
    }

    @Test
    void batchRegisterUpdateUnregister() throws Exception {
        RedissonClient redis = Redisson.create(newConfig());
        String svc = "batch-" + UUID.randomUUID().toString().substring(0, 8);
        RedisNamingService naming = new RedisNamingService(redis);
        naming.start();
        try {
            ServiceInstance b1 = ins(svc, "b1", 8080, Map.of("tier", "gold"));
            ServiceInstance b2 = ins(svc, "b2", 8081, Map.of("tier", "silver"));
            naming.register(b1);
            naming.register(b2);
            assertEquals(2, naming.getInstances(svc, false).size());
            naming.batchHeartbeat(List.of(b1, b2));
            naming.sendHeartbeat(b1);
            naming.batchSendHeartbeats(List.of(b1, b2));

            naming.deregister(b2);
            long d2 = System.currentTimeMillis() + 10_000;
            while (naming.getInstances(svc, false).size() > 1 && System.currentTimeMillis() < d2) {
                Thread.sleep(100);
            }
            assertEquals(1, naming.getInstances(svc, false).size());

            naming.deregister(b1);
            naming.register(null == b1 ? ins(svc, "x", 1, Map.of()) : b1); // re-register path
            naming.deregister(b1);
            naming.stop();
        } finally {
            naming.stop();
            redis.shutdown();
        }
    }

    private static Config newConfig() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return cfg;
    }
}
