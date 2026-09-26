package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.client.ClientInvoker;
import io.github.cuihairu.redis.streaming.registry.client.RetryPolicy;
import io.github.cuihairu.redis.streaming.registry.client.metrics.RedisClientMetricsReporter;
import io.github.cuihairu.redis.streaming.registry.impl.RedisNamingService;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRoundRobinLoadBalancer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class ClientInvokerIntegrationTest {

    @Test
    void testSuccessPathNoRetry() throws Exception {
        RedissonClient client = createClient();
        try {
            RedisNamingService naming = new RedisNamingService(client);
            naming.start();
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName("svc-inv")
                    .instanceId("i1")
                    .host("127.0.0.1")
                    .port(8080)
                    .build();
            naming.register(inst);

            ClientInvoker invoker = new ClientInvoker(
                    naming,
                    new WeightedRoundRobinLoadBalancer(),
                    new RetryPolicy(3, 1, 2.0, 10, 0),
                    null // metrics reporter not needed here
            );

            String res = invoker.invoke("svc-inv", Map.of(), Map.of(), Map.of(), si -> "OK:" + si.getInstanceId());
            assertEquals("OK:i1", res);
            var snap = invoker.getMetricsSnapshot();
            assertTrue(snap.get("total").get("attempts") >= 1);
            assertTrue(snap.get("total").get("successes") >= 1);

            naming.stop();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void testFailureTriggersCircuitBreakerSkips() {
        RedissonClient client = createClient();
        try {
            RedisNamingService naming = new RedisNamingService(client);
            naming.start();
            ServiceInstance inst = DefaultServiceInstance.builder()
                    .serviceName("svc-inv-f")
                    .instanceId("i1")
                    .host("127.0.0.1")
                    .port(8080)
                    .build();
            naming.register(inst);

            ClientInvoker invoker = new ClientInvoker(
                    naming,
                    new WeightedRoundRobinLoadBalancer(),
                    new RetryPolicy(3, 1, 2.0, 10, 0),
                    null
            );

            // B-32: the breaker judges only once its 20-call window fills. Each invoke makes
            // 3 attempts, so 7 failing invokes fill the window with failures and trip it.
            for (int i = 0; i < 7; i++) {
                assertThrows(Exception.class, () -> invoker.invoke(
                        "svc-inv-f", Map.of(), Map.of(), Map.of(), si -> { throw new RuntimeException("boom"); }
                ));
            }
            // window full with rate 1.0 >= 0.5 -> CB open, further invokes are skipped
            Exception open = assertThrows(Exception.class, () -> invoker.invoke(
                    "svc-inv-f", Map.of(), Map.of(), Map.of(), si -> "ok"
            ));
            assertTrue(open.getMessage().contains("Circuit breaker"));
            var snap = invoker.getMetricsSnapshot();
            assertTrue(snap.get("total").get("attempts") >= 1);
            assertTrue(snap.get("total").getOrDefault("cbOpenSkips", 0L) >= 1);

            naming.stop();
        } finally {
            client.shutdown();
        }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl);
        return Redisson.create(config);
    }
}

