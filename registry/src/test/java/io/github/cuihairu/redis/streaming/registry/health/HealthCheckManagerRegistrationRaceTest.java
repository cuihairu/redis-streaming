package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for B-26: concurrent registration of the same service instance used
 * to pass the containsKey check-then-act and start one probe thread per racing caller;
 * the overwritten duplicates were never stopped by unregister (it only stops the entry
 * left in the map).
 */
class HealthCheckManagerRegistrationRaceTest {

    @Test
    void concurrentRegistrationStartsExactlyOneChecker() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        // stub probe: cheap, records how many checkers actually ran their initial check
        HealthChecker stub = inst -> {
            probes.incrementAndGet();
            return true;
        };

        HealthCheckManager manager = new HealthCheckManager(
                stub,
                (uniqueId, isHealthy) -> { },
                1, TimeUnit.HOURS);

        ServiceInstance instance = DefaultServiceInstance.builder()
                .serviceName("svc-race").instanceId("i1")
                .host("127.0.0.1").port(8080)
                .protocol(io.github.cuihairu.redis.streaming.registry.StandardProtocol.TCP)
                .build();

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    manager.registerServiceInstance(instance);
                    return null;
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, manager.getHealthCheckerCount(),
                "exactly one checker may be registered for an instance");

        Map<String, ClientHealthChecker> checkers = getCheckers(manager);
        ClientHealthChecker checker = checkers.values().iterator().next();
        assertTrue(checker.isRunning());
        assertEquals(1, probes.get(),
                "only the winning checker may start (one initial probe); losers must stay inert");

        // and unregister stops the single checker — nothing left probing
        manager.unregisterServiceInstance(instance.getUniqueId());
        assertEquals(0, manager.getHealthCheckerCount());
        assertFalse(checker.isRunning());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ClientHealthChecker> getCheckers(HealthCheckManager manager) throws Exception {
        java.lang.reflect.Field f = HealthCheckManager.class.getDeclaredField("healthCheckers");
        f.setAccessible(true);
        return (Map<String, ClientHealthChecker>) f.get(manager);
    }
}
