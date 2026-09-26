package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-43 regression: a collector that exceeds the collection timeout left its task
 * running (uninterrupted) on the common ForkJoinPool — repeated timeouts piled
 * blocked probes onto the JVM-wide pool. The collection must run off the common
 * pool and the timed-out task must be cancelled.
 *
 * <p>Uses only the public API; the thread-stack scan reproduces on the pre-fix code.
 */
class MetricsCollectionTimeoutTest {

    @Test
    void timedOutCollectorDoesNotOccupyTheCommonPool() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch probeMayFinish = new CountDownLatch(1);

        MetricCollector hungCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "hung";
            }

            @Override
            public Object collectMetric() throws Exception {
                probeStarted.countDown();
                // blocks well past the timeout; interrupts (the B-43 cancel) end it early
                probeMayFinish.await(10, TimeUnit.SECONDS);
                return Map.of("x", 1);
            }
        };

        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(java.util.Set.of("hung"));
        cfg.setCollectionTimeout(java.time.Duration.ofMillis(150));

        MetricsCollectionManager manager = new MetricsCollectionManager(List.of(hungCollector), cfg);
        try {
            long begin = System.currentTimeMillis();
            manager.collectMetrics(true); // timeout is swallowed per-collector with a WARN
            long elapsed = System.currentTimeMillis() - begin;
            assertTrue(probeStarted.await(3, TimeUnit.SECONDS));
            assertTrue(elapsed < 5_000, "collection must return at the timeout, took " + elapsed + "ms");

            // give the cancel a moment to take effect, then inspect live thread stacks
            Thread.sleep(300);
            assertNull(commonPoolCollectorThread(),
                    "a timed-out collector must not linger on the common ForkJoinPool (B-43)");
        } finally {
            probeMayFinish.countDown();
        }
    }

    private static StackTraceElement[] commonPoolCollectorThread() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            String name = e.getKey().getName();
            if (!name.startsWith("ForkJoinPool.commonPool")) {
                continue;
            }
            for (StackTraceElement frame : e.getValue()) {
                if (frame.getMethodName().equals("collectMetric")) {
                    return e.getValue();
                }
            }
        }
        return null;
    }
}
