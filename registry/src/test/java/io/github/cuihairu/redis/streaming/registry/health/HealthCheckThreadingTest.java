package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-08 regression: health checks must not run inline on the thread that calls
 * {@code start()}/{@code registerServiceInstance()} (it used to block for a full
 * connect+read timeout per instance), scheduling threads must be daemons, and the
 * manager must share one pool instead of spawning a non-daemon thread per instance.
 *
 * <p>Uses only the public pre-fix API so the file doubles as the old-code reproduction.
 */
class HealthCheckThreadingTest {

    private static ServiceInstance instance(String id) {
        return new TestServiceInstance("b08", id, "127.0.0.1", 1, StandardProtocol.TCP);
    }

    @Test
    void startDoesNotRunTheFirstCheckOnTheCallingThread() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        List<String> checkThreads = new CopyOnWriteArrayList<>();
        String uniqueId = "sync-" + UUID.randomUUID().toString().substring(0, 8);

        ClientHealthChecker checker = new ClientHealthChecker(
                instance(uniqueId),
                si -> {
                    checkThreads.add(Thread.currentThread().getName());
                    checked.countDown();
                    return true;
                },
                ok -> { },
                10, TimeUnit.MILLISECONDS);
        try {
            checker.start();
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread().getName(), checkThreads.get(0),
                    "the first check must run off the calling thread (old code ran it inline)");
        } finally {
            checker.stop();
        }
    }

    @Test
    void checkerThreadIsADaemon() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        String uniqueId = "daemon-" + UUID.randomUUID().toString().substring(0, 8);
        ClientHealthChecker checker = new ClientHealthChecker(
                instance(uniqueId),
                si -> {
                    checked.countDown();
                    return true;
                },
                ok -> { },
                10, TimeUnit.MILLISECONDS);
        try {
            checker.start();
            assertTrue(checked.await(3, TimeUnit.SECONDS));

            Thread schedulerThread = findThread("health-checker-" + uniqueId);
            assertNotNull(schedulerThread, "the scheduling thread should exist while running");
            assertTrue(schedulerThread.isDaemon(),
                    "a leaked checker must not keep the JVM from exiting (B-08)");
        } finally {
            checker.stop();
        }
    }

    @Test
    void managerSharesOnePoolAcrossInstances() throws Exception {
        int instances = 5;
        CountDownLatch allChecked = new CountDownLatch(instances);
        List<String> checkThreads = new CopyOnWriteArrayList<>();

        HealthCheckManager manager = new HealthCheckManager(
                si -> {
                    checkThreads.add(Thread.currentThread().getName());
                    allChecked.countDown();
                    return true;
                },
                (id, ok) -> { },
                10, TimeUnit.MILLISECONDS);
        try {
            for (int i = 0; i < instances; i++) {
                manager.registerServiceInstance(instance("pool-" + UUID.randomUUID().toString().substring(0, 8) + "-" + i));
            }
            assertTrue(allChecked.await(3, TimeUnit.SECONDS));

            for (String threadName : checkThreads) {
                assertTrue(threadName.startsWith("health-check-manager"),
                        "checks must run on the shared manager pool, saw: " + threadName);
            }
            assertTrue(checkThreads.size() >= instances);
        } finally {
            manager.stopAll();
        }
    }

    private static Thread findThread(String name) {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (name.equals(e.getKey().getName())) {
                return e.getKey();
            }
        }
        return null;
    }
}
