package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HealthCheckManagerTest {

    @Test
    public void testRegisterAndReportHealthChange() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> reportedId = new AtomicReference<>();
        AtomicReference<Boolean> reportedStatus = new AtomicReference<>();

        HealthCheckManager manager = new HealthCheckManager(
                null,
                (id, healthy) -> {
                    reportedId.set(id);
                    reportedStatus.set(healthy);
                    latch.countDown();
                },
                10,
                TimeUnit.MILLISECONDS
        );

        manager.registerProtocolHealthChecker(StandardProtocol.TCP, si -> false);

        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.TCP);
        String uniqueId = instance.getUniqueId();

        manager.registerServiceInstance(instance);
        try {
            assertEquals(1, manager.getHealthCheckerCount());
            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertEquals(uniqueId, reportedId.get());
            assertEquals(Boolean.FALSE, reportedStatus.get());
            assertFalse(manager.isInstanceHealthy(uniqueId));
        } finally {
            manager.stopAll();
        }
    }

    @Test
    public void testNoCheckerMeansNotRegistered() {
        HealthCheckManager manager = new HealthCheckManager(
                null,
                (id, healthy) -> {},
                10,
                TimeUnit.MILLISECONDS
        );

        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.TCP);
        manager.registerServiceInstance(instance);
        assertEquals(0, manager.getHealthCheckerCount());
    }
}

