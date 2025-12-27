package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ClientHealthCheckerTest {

    @Test
    public void testReportsUnhealthyOnFirstCheck() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> reported = new AtomicReference<>();

        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.TCP);
        ClientHealthChecker checker = new ClientHealthChecker(
                instance,
                si -> false,
                isHealthy -> {
                    reported.set(isHealthy);
                    latch.countDown();
                },
                10,
                TimeUnit.MILLISECONDS
        );

        try {
            checker.start();
            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, reported.get());
            assertFalse(checker.getLastHealthStatus());
        } finally {
            checker.stop();
        }
    }

    @Test
    public void testStartStopIdempotent() {
        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.TCP);
        ClientHealthChecker checker = new ClientHealthChecker(
                instance,
                si -> true,
                ignored -> {},
                10,
                TimeUnit.MILLISECONDS
        );

        checker.start();
        checker.start();
        assertTrue(checker.isRunning());

        checker.stop();
        checker.stop();
        assertFalse(checker.isRunning());
    }
}

