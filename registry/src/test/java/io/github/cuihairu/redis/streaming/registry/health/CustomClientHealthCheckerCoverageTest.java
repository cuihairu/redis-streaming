package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers CustomHealthChecker (including isPortReachable) and ClientHealthChecker
 * checkHealth/stop paths using local sockets only.
 */
class CustomClientHealthCheckerCoverageTest {

    private static ServiceInstance instance(int port) {
        return DefaultServiceInstance.builder()
                .serviceName("health-cov").instanceId("i-" + port).host("127.0.0.1").port(port)
                .protocol(StandardProtocol.TCP).metadata(Map.of()).healthy(true).build();
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void customHealthCheckerRunsTcpThenDoCheck() throws Exception {
        AtomicBoolean doCheckCalled = new AtomicBoolean();
        CustomHealthChecker healthy = new CustomHealthChecker() {
            @Override
            protected boolean doCheck(ServiceInstance serviceInstance) {
                doCheckCalled.set(true);
                return true;
            }
        };

        try (ServerSocket open = new ServerSocket(0)) {
            assertTrue(healthy.check(instance(open.getLocalPort())));
            assertTrue(doCheckCalled.get());

            CustomHealthChecker unhealthy = new CustomHealthChecker() {
                @Override
                protected boolean doCheck(ServiceInstance serviceInstance) {
                    return false;
                }
            };
            assertFalse(unhealthy.check(instance(open.getLocalPort())));
        }

        int closed = closedPort();
        doCheckCalled.set(false);
        CustomHealthChecker neverCalled = new CustomHealthChecker() {
            @Override
            protected boolean doCheck(ServiceInstance serviceInstance) {
                doCheckCalled.set(true);
                return true;
            }
        };
        assertFalse(neverCalled.check(instance(closed)));
        assertFalse(doCheckCalled.get(), "doCheck must be skipped when the port is unreachable");
    }

    @Test
    void customHealthCheckerPropagatesDoCheckFailure() {
        CustomHealthChecker throwing = new CustomHealthChecker() {
            @Override
            protected boolean doCheck(ServiceInstance serviceInstance) throws Exception {
                throw new Exception("business failure");
            }
        };
        try (ServerSocket open = new ServerSocket(0)) {
            assertThrows(Exception.class, () -> throwing.check(instance(open.getLocalPort())));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void clientHealthCheckerReportsTransitionsAndFailures() throws Exception {
        CountDownLatch firstReport = new CountDownLatch(1);
        AtomicBoolean healthyFlag = new AtomicBoolean(false);
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(closedPort()),
                si -> healthyFlag.get(),
                ok -> firstReport.countDown(),
                50, TimeUnit.MILLISECONDS);
        try {
            checker.start();
            assertTrue(checker.isRunning());
            assertTrue(firstReport.await(3, TimeUnit.SECONDS), "initial healthy->unhealthy transition should report");
            assertFalse(checker.getLastHealthStatus());

            // recovery: healthy again -> another report
            CountDownLatch recovery = new CountDownLatch(1);
            ClientHealthChecker recoveryChecker = new ClientHealthChecker(
                    instance(closedPort()),
                    si -> true,
                    ok -> recovery.countDown(),
                    50, TimeUnit.MILLISECONDS);
            try {
                // start with checker that says false first, then true
                AtomicInteger calls = new AtomicInteger();
                ClientHealthChecker flapping = new ClientHealthChecker(
                        instance(closedPort()),
                        si -> calls.incrementAndGet() > 1,
                        ok -> recovery.countDown(),
                        50, TimeUnit.MILLISECONDS);
                flapping.start();
                assertTrue(recovery.await(3, TimeUnit.SECONDS));
                flapping.stop();
                assertFalse(flapping.isRunning());
            } finally {
                recoveryChecker.stop();
            }
        } finally {
            checker.stop();
        }
    }

    @Test
    void clientHealthCheckerReportsFalseWhenCheckThrows() throws Exception {
        CountDownLatch report = new CountDownLatch(1);
        AtomicBoolean reported = new AtomicBoolean();
        Consumer<Boolean> reporter = ok -> {
            reported.set(ok);
            report.countDown();
        };
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(closedPort()),
                si -> {
                    throw new IllegalStateException("probe failure");
                },
                reporter, 50, TimeUnit.MILLISECONDS);
        try {
            checker.start();
            assertTrue(report.await(3, TimeUnit.SECONDS));
            assertFalse(reported.get());
            assertFalse(checker.getLastHealthStatus());
        } finally {
            checker.stop();
        }
    }

    @Test
    void clientHealthCheckerStopIsIdempotentAndSafeWithoutStart() throws Exception {
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(closedPort()), si -> true, ok -> { }, 50, TimeUnit.MILLISECONDS);
        checker.stop(); // not running -> early return
        checker.start();
        checker.stop();
        checker.stop(); // second stop -> early return
    }

    @Test
    void healthCheckManagerUnregisterAndHealthyLookup() throws Exception {
        java.util.List<Boolean> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
        HealthCheckManager manager = new HealthCheckManager(
                si -> true, (id, ok) -> reports.add(ok), 50, TimeUnit.MILLISECONDS);
        manager.registerProtocolHealthChecker(StandardProtocol.TCP, si -> true);
        TestServiceInstance first = new TestServiceInstance("mgr", "m1", "127.0.0.1", 1, StandardProtocol.TCP);
        manager.registerServiceInstance(first);
        try {
            String uniqueId = first.getUniqueId();
            assertTrue(manager.getHealthCheckerCount() == 1);
            assertTrue(manager.isInstanceHealthy(uniqueId));
            assertFalse(manager.isInstanceHealthy("mgr:absent"));

            manager.unregisterServiceInstance(uniqueId);
            assertFalse(manager.isInstanceHealthy(uniqueId));
            assertEquals(0, manager.getHealthCheckerCount());

            TestServiceInstance second = new TestServiceInstance("mgr", "m2", "127.0.0.1", 1, StandardProtocol.TCP);
            manager.registerServiceInstance(second);
            assertEquals(1, manager.getHealthCheckerCount());
            manager.unregisterServiceInstance(second);
            assertEquals(0, manager.getHealthCheckerCount());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void clientHealthCheckerStopTimesOutOnStuckProbe() throws Exception {
        java.util.concurrent.CountDownLatch slowStarted = new java.util.concurrent.CountDownLatch(1);
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(closedPort()),
                si -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(8_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                },
                ok -> { }, 20, TimeUnit.MILLISECONDS);
        // start() runs the first probe inline in a separate thread via scheduler after the sync call;
        // make the synchronous first call fast by flipping after the first invocation
        ClientHealthChecker blocking = new ClientHealthChecker(
                instance(closedPort()),
                new HealthChecker() {
                    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

                    @Override
                    public boolean check(ServiceInstance serviceInstance) throws Exception {
                        if (calls.incrementAndGet() > 1) {
                            slowStarted.countDown();
                            Thread.sleep(8_000);
                        }
                        return true;
                    }
                },
                ok -> { }, 20, TimeUnit.MILLISECONDS);
        blocking.start();
        assertTrue(slowStarted.await(3, TimeUnit.SECONDS));
        long begin = System.currentTimeMillis();
        blocking.stop();
        assertTrue(System.currentTimeMillis() - begin >= 4_500, "stop should wait for the stuck probe to time out");
    }

    @Test
    void clientHealthCheckerDoesNotReportWhenAlreadyUnhealthy() throws Exception {
        java.util.List<Boolean> reports = new java.util.concurrent.CopyOnWriteArrayList<>();
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(closedPort()),
                si -> {
                    throw new IllegalStateException("always failing");
                },
                reports::add, 50, TimeUnit.MILLISECONDS);
        try {
            checker.start();
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline && reports.size() < 2) {
                Thread.sleep(50);
            }
            assertEquals(1, reports.size(), "only the first failure transition should be reported");
        } finally {
            checker.stop();
        }
    }

    @Test
    void clientHealthCheckerStopInterruptsAwait() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(closedPort()),
                si -> {
                    started.countDown();
                    return true;
                },
                ok -> { }, 20, TimeUnit.MILLISECONDS);
        checker.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            checker.stop();
            assertTrue(Thread.interrupted(), "interrupt flag should be re-raised after InterruptedException");
        } finally {
            Thread.interrupted(); // clear flag
        }
    }
}
