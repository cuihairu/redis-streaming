package io.github.cuihairu.redis.streaming.registry.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClientInvokerMetrics
 */
class ClientInvokerMetricsTest {

    private ClientInvokerMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ClientInvokerMetrics();
    }

    @Test
    void testConstructor() {
        assertNotNull(metrics);
    }

    @Test
    void testTotalCounters() {
        ClientInvokerMetrics.Counters total = metrics.total();

        assertNotNull(total);
        assertEquals(0, total.attempts.get());
        assertEquals(0, total.successes.get());
        assertEquals(0, total.failures.get());
        assertEquals(0, total.retries.get());
        assertEquals(0, total.cbOpenSkips.get());
    }

    @Test
    void testForServiceNewService() {
        ClientInvokerMetrics.Counters serviceCounters = metrics.forService("test-service");

        assertNotNull(serviceCounters);
        assertEquals(0, serviceCounters.attempts.get());
        assertEquals(0, serviceCounters.successes.get());
        assertEquals(0, serviceCounters.failures.get());
        assertEquals(0, serviceCounters.retries.get());
        assertEquals(0, serviceCounters.cbOpenSkips.get());
    }

    @Test
    void testForServiceSameInstance() {
        ClientInvokerMetrics.Counters counters1 = metrics.forService("test-service");
        ClientInvokerMetrics.Counters counters2 = metrics.forService("test-service");

        assertSame(counters1, counters2);
    }

    @Test
    void testForServiceDifferentServices() {
        ClientInvokerMetrics.Counters counters1 = metrics.forService("service-1");
        ClientInvokerMetrics.Counters counters2 = metrics.forService("service-2");

        assertNotSame(counters1, counters2);
    }

    @Test
    void testIncrementTotalAttempts() {
        metrics.total().attempts.incrementAndGet();
        assertEquals(1, metrics.total().attempts.get());
    }

    @Test
    void testIncrementServiceAttempts() {
        metrics.forService("test-service").attempts.incrementAndGet();
        assertEquals(1, metrics.forService("test-service").attempts.get());
    }

    @Test
    void testIncrementMultipleCounters() {
        ClientInvokerMetrics.Counters total = metrics.total();
        total.attempts.incrementAndGet();
        total.successes.incrementAndGet();
        total.failures.incrementAndGet();
        total.retries.incrementAndGet();
        total.cbOpenSkips.incrementAndGet();

        assertEquals(1, total.attempts.get());
        assertEquals(1, total.successes.get());
        assertEquals(1, total.failures.get());
        assertEquals(1, total.retries.get());
        assertEquals(1, total.cbOpenSkips.get());
    }

    @Test
    void testSnapshot() {
        metrics.total().attempts.incrementAndGet();
        metrics.total().successes.incrementAndGet();
        metrics.forService("test-service").attempts.incrementAndGet();
        metrics.forService("test-service").failures.incrementAndGet();

        Map<String, Map<String, Long>> snapshot = metrics.snapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey("total"));
        assertTrue(snapshot.containsKey("test-service"));

        Map<String, Long> totalSnapshot = snapshot.get("total");
        assertEquals(1, totalSnapshot.get("attempts"));
        assertEquals(1, totalSnapshot.get("successes"));

        Map<String, Long> serviceSnapshot = snapshot.get("test-service");
        assertEquals(1, serviceSnapshot.get("attempts"));
        assertEquals(1, serviceSnapshot.get("failures"));
    }

    @Test
    void testSnapshotWithEmptyMetrics() {
        Map<String, Map<String, Long>> snapshot = metrics.snapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey("total"));

        Map<String, Long> totalSnapshot = snapshot.get("total");
        assertEquals(0, totalSnapshot.get("attempts"));
        assertEquals(0, totalSnapshot.get("successes"));
        assertEquals(0, totalSnapshot.get("failures"));
        assertEquals(0, totalSnapshot.get("retries"));
        assertEquals(0, totalSnapshot.get("cbOpenSkips"));
    }

    @Test
    void testSnapshotContainsAllCounterFields() {
        metrics.total().attempts.incrementAndGet();
        metrics.total().successes.incrementAndGet();
        metrics.total().failures.incrementAndGet();
        metrics.total().retries.incrementAndGet();
        metrics.total().cbOpenSkips.incrementAndGet();

        Map<String, Long> snapshot = metrics.snapshot().get("total");

        assertTrue(snapshot.containsKey("attempts"));
        assertTrue(snapshot.containsKey("successes"));
        assertTrue(snapshot.containsKey("failures"));
        assertTrue(snapshot.containsKey("retries"));
        assertTrue(snapshot.containsKey("cbOpenSkips"));
    }

    @Test
    void testMultipleServicesSnapshot() {
        metrics.forService("service-1").attempts.incrementAndGet();
        metrics.forService("service-1").successes.incrementAndGet();
        metrics.forService("service-2").attempts.incrementAndGet();
        metrics.forService("service-2").failures.incrementAndGet();

        Map<String, Map<String, Long>> snapshot = metrics.snapshot();

        assertTrue(snapshot.containsKey("service-1"));
        assertTrue(snapshot.containsKey("service-2"));

        assertEquals(1, snapshot.get("service-1").get("successes"));
        assertEquals(1, snapshot.get("service-2").get("failures"));
    }

    @Test
    void testConcurrentServiceAccess() {
        ClientInvokerMetrics.Counters counters1 = metrics.forService("service-1");
        ClientInvokerMetrics.Counters counters2 = metrics.forService("service-2");

        counters1.attempts.incrementAndGet();
        counters2.attempts.incrementAndGet();

        assertEquals(1, counters1.attempts.get());
        assertEquals(1, counters2.attempts.get());
        assertEquals(0, metrics.forService("service-3").attempts.get());
    }

    @Test
    void testAtomicOperations() {
        ClientInvokerMetrics.Counters counters = metrics.total();

        // Multiple increments
        counters.attempts.addAndGet(5);
        assertEquals(5, counters.attempts.get());

        // Compare and set
        assertTrue(counters.attempts.compareAndSet(5, 10));
        assertEquals(10, counters.attempts.get());

        // Failed compare and set
        assertFalse(counters.attempts.compareAndSet(5, 20));
        assertEquals(10, counters.attempts.get());
    }

    @Test
    void testSnapshotIsIndependentFromMetrics() {
        metrics.total().attempts.incrementAndGet();
        metrics.forService("test-service").attempts.incrementAndGet();

        Map<String, Map<String, Long>> snapshot1 = metrics.snapshot();

        // Modify metrics after snapshot
        metrics.total().attempts.incrementAndGet();
        metrics.forService("test-service").attempts.incrementAndGet();

        Map<String, Map<String, Long>> snapshot2 = metrics.snapshot();

        // Snapshots should be independent
        assertEquals(1, snapshot1.get("total").get("attempts"));
        assertEquals(2, snapshot2.get("total").get("attempts"));
    }
}
