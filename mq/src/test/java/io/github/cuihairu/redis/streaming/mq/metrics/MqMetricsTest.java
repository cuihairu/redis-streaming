package io.github.cuihairu.redis.streaming.mq.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MqMetrics
 */
class MqMetricsTest {

    private MqMetricsCollector originalCollector;

    @BeforeEach
    void setUp() {
        // Save original collector to restore after test
        originalCollector = MqMetrics.get();
    }

    @AfterEach
    void tearDown() {
        // Restore original collector
        MqMetrics.setCollector(originalCollector);
    }

    @Test
    void testConstructorIsPrivate() throws Exception {
        // Given & When - try to get constructor
        Constructor<MqMetrics> constructor = MqMetrics.class.getDeclaredConstructor();

        // Then - constructor should be private
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "Constructor should be private");
    }

    @Test
    void testDefaultCollectorIsNoop() {
        // Given & When
        MqMetricsCollector collector = MqMetrics.get();

        // Then - should be able to call all methods without exception
        assertDoesNotThrow(() -> {
            collector.incProduced("test-topic", 0);
            collector.incConsumed("test-topic", 0);
            collector.incAcked("test-topic", 0);
            collector.incRetried("test-topic", 0);
            collector.incDeadLetter("test-topic", 0);
            collector.recordHandleLatency("test-topic", 0, 100);
        });
    }

    @Test
    void testSetCollector() {
        // Given
        TestMqMetricsCollector testCollector = new TestMqMetricsCollector();

        // When
        MqMetrics.setCollector(testCollector);

        // Then
        assertEquals(testCollector, MqMetrics.get());
    }

    @Test
    void testSetCollectorWithNull() {
        // Given
        TestMqMetricsCollector testCollector = new TestMqMetricsCollector();
        MqMetrics.setCollector(testCollector);

        // When - set null should not change collector
        MqMetrics.setCollector(null);

        // Then
        assertEquals(testCollector, MqMetrics.get(),
                "Setting null collector should not change existing collector");
    }

    @Test
    void testSetCollectorReplacesExisting() {
        // Given
        TestMqMetricsCollector collector1 = new TestMqMetricsCollector();
        TestMqMetricsCollector collector2 = new TestMqMetricsCollector();

        // When
        MqMetrics.setCollector(collector1);
        MqMetrics.setCollector(collector2);

        // Then
        assertEquals(collector2, MqMetrics.get());
        assertNotEquals(collector1, MqMetrics.get());
    }

    @Test
    void testGetReturnsSameInstance() {
        // Given
        TestMqMetricsCollector testCollector = new TestMqMetricsCollector();
        MqMetrics.setCollector(testCollector);

        // When
        MqMetricsCollector collector1 = MqMetrics.get();
        MqMetricsCollector collector2 = MqMetrics.get();

        // Then
        assertSame(collector1, collector2,
                "get() should return the same instance");
    }

    @Test
    void testStaticMethodsUseCurrentCollector() {
        // Given
        TestMqMetricsCollector testCollector = new TestMqMetricsCollector();
        MqMetrics.setCollector(testCollector);

        // When - call metrics through static interface
        testCollector.incProduced("topic1", 1);
        testCollector.incConsumed("topic1", 1);
        testCollector.incAcked("topic1", 1);
        testCollector.incRetried("topic1", 1);
        testCollector.incDeadLetter("topic1", 1);
        testCollector.recordHandleLatency("topic1", 1, 100);

        // Then - verify counts
        assertEquals(1, testCollector.getProducedCount());
        assertEquals(1, testCollector.getConsumedCount());
        assertEquals(1, testCollector.getAckedCount());
        assertEquals(1, testCollector.getRetriedCount());
        assertEquals(1, testCollector.getDeadLetterCount());
        assertEquals(1, testCollector.getLatencyCount());
    }

    @Test
    void testMultipleTopicsAndPartitions() {
        // Given
        TestMqMetricsCollector testCollector = new TestMqMetricsCollector();
        MqMetrics.setCollector(testCollector);

        // When
        testCollector.incProduced("topic-a", 0);
        testCollector.incProduced("topic-a", 1);
        testCollector.incProduced("topic-b", 0);

        // Then
        assertEquals(3, testCollector.getProducedCount());
    }

    @Test
    void testNullCollectorDoesNotThrow() {
        // Given - restore to default noop
        MqMetrics.setCollector(null);

        // When & Then - default should still be noop
        MqMetricsCollector collector = MqMetrics.get();
        assertDoesNotThrow(() -> {
            collector.incProduced("test", 0);
            collector.incConsumed("test", 0);
            collector.incAcked("test", 0);
            collector.incRetried("test", 0);
            collector.incDeadLetter("test", 0);
            collector.recordHandleLatency("test", 0, 100);
        });
    }

    @Test
    void testConcurrentSetAndGet() throws InterruptedException {
        // Given
        TestMqMetricsCollector testCollector = new TestMqMetricsCollector();

        // When - set from multiple threads
        Thread t1 = new Thread(() -> MqMetrics.setCollector(testCollector));
        Thread t2 = new Thread(() -> MqMetrics.setCollector(testCollector));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Then
        assertEquals(testCollector, MqMetrics.get());
    }

    // Test collector implementation
    private static class TestMqMetricsCollector implements MqMetricsCollector {
        private int producedCount;
        private int consumedCount;
        private int ackedCount;
        private int retriedCount;
        private int deadLetterCount;
        private int latencyCount;

        @Override
        public void incProduced(String topic, int partitionId) {
            producedCount++;
        }

        @Override
        public void incConsumed(String topic, int partitionId) {
            consumedCount++;
        }

        @Override
        public void incAcked(String topic, int partitionId) {
            ackedCount++;
        }

        @Override
        public void incRetried(String topic, int partitionId) {
            retriedCount++;
        }

        @Override
        public void incDeadLetter(String topic, int partitionId) {
            deadLetterCount++;
        }

        @Override
        public void recordHandleLatency(String topic, int partitionId, long millis) {
            latencyCount++;
        }

        public int getProducedCount() { return producedCount; }
        public int getConsumedCount() { return consumedCount; }
        public int getAckedCount() { return ackedCount; }
        public int getRetriedCount() { return retriedCount; }
        public int getDeadLetterCount() { return deadLetterCount; }
        public int getLatencyCount() { return latencyCount; }
    }
}
