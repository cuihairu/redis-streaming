package io.github.cuihairu.redis.streaming.mq.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetentionMetrics
 */
class RetentionMetricsTest {

    private RetentionMetricsCollector originalCollector;

    @BeforeEach
    void setUp() {
        // Save original collector to restore after test
        originalCollector = RetentionMetrics.get();
    }

    @AfterEach
    void tearDown() {
        // Restore original collector
        RetentionMetrics.setCollector(originalCollector);
    }

    @Test
    void testConstructorIsPrivate() throws Exception {
        // Given & When - try to get constructor
        Constructor<RetentionMetrics> constructor = RetentionMetrics.class.getDeclaredConstructor();

        // Then - constructor should be private
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "Constructor should be private");
    }

    @Test
    void testDefaultCollectorIsNoop() {
        // Given & When
        RetentionMetricsCollector collector = RetentionMetrics.get();

        // Then - should be able to call all methods without exception
        assertDoesNotThrow(() -> {
            collector.recordTrim("test-topic", 0, 100, "test-reason");
            collector.recordDlqTrim("test-topic", 50, "dlq-reason");
        });
    }

    @Test
    void testSetCollector() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();

        // When
        RetentionMetrics.setCollector(testCollector);

        // Then
        assertEquals(testCollector, RetentionMetrics.get());
    }

    @Test
    void testSetCollectorWithNull() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When - set null should not change collector
        RetentionMetrics.setCollector(null);

        // Then
        assertEquals(testCollector, RetentionMetrics.get(),
                "Setting null collector should not change existing collector");
    }

    @Test
    void testSetCollectorReplacesExisting() {
        // Given
        TestRetentionMetricsCollector collector1 = new TestRetentionMetricsCollector();
        TestRetentionMetricsCollector collector2 = new TestRetentionMetricsCollector();

        // When
        RetentionMetrics.setCollector(collector1);
        RetentionMetrics.setCollector(collector2);

        // Then
        assertEquals(collector2, RetentionMetrics.get());
        assertNotEquals(collector1, RetentionMetrics.get());
    }

    @Test
    void testGetReturnsSameInstance() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        RetentionMetricsCollector collector1 = RetentionMetrics.get();
        RetentionMetricsCollector collector2 = RetentionMetrics.get();

        // Then
        assertSame(collector1, collector2,
                "get() should return the same instance");
    }

    @Test
    void testRecordTrim() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordTrim("topic1", 0, 100, "maxlen");

        // Then
        assertEquals(1, testCollector.getTrimCount());
        assertEquals("topic1", testCollector.getLastTopic());
        assertEquals(0, testCollector.getLastPartitionId());
        assertEquals(100, testCollector.getLastDeleted());
        assertEquals("maxlen", testCollector.getLastReason());
    }

    @Test
    void testRecordDlqTrim() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordDlqTrim("topic1", 50, "expired");

        // Then
        assertEquals(1, testCollector.getDlqTrimCount());
        assertEquals("topic1", testCollector.getLastDlqTopic());
        assertEquals(50, testCollector.getLastDlqDeleted());
        assertEquals("expired", testCollector.getLastDlqReason());
    }

    @Test
    void testMultipleTrims() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordTrim("topic-a", 0, 100, "maxlen");
        testCollector.recordTrim("topic-b", 1, 200, "maxlen");

        // Then
        assertEquals(2, testCollector.getTrimCount());
    }

    @Test
    void testMultipleDlqTrims() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordDlqTrim("topic-a", 50, "expired");
        testCollector.recordDlqTrim("topic-b", 75, "maxlen");

        // Then
        assertEquals(2, testCollector.getDlqTrimCount());
    }

    @Test
    void testNullCollectorDoesNotThrow() {
        // Given - restore to default noop
        RetentionMetrics.setCollector(null);

        // When & Then - default should still be noop
        RetentionMetricsCollector collector = RetentionMetrics.get();
        assertDoesNotThrow(() -> {
            collector.recordTrim("test", 0, 100, "reason");
            collector.recordDlqTrim("test", 50, "reason");
        });
    }

    @Test
    void testConcurrentSetAndGet() throws InterruptedException {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();

        // When - set from multiple threads
        Thread t1 = new Thread(() -> RetentionMetrics.setCollector(testCollector));
        Thread t2 = new Thread(() -> RetentionMetrics.setCollector(testCollector));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Then
        assertEquals(testCollector, RetentionMetrics.get());
    }

    @Test
    void testTrimWithDifferentReasons() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordTrim("topic1", 0, 100, "maxlen");
        testCollector.recordTrim("topic1", 0, 50, "approx");
        testCollector.recordTrim("topic1", 0, 25, "minid");

        // Then
        assertEquals(3, testCollector.getTrimCount());
    }

    @Test
    void testDlqTrimWithDifferentReasons() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordDlqTrim("topic1", 100, "maxlen");
        testCollector.recordDlqTrim("topic1", 50, "approx");
        testCollector.recordDlqTrim("topic1", 25, "expired");

        // Then
        assertEquals(3, testCollector.getDlqTrimCount());
    }

    @Test
    void testTrimWithZeroDeleted() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordTrim("topic1", 0, 0, "no-op");

        // Then
        assertEquals(1, testCollector.getTrimCount());
        assertEquals(0, testCollector.getLastDeleted());
    }

    @Test
    void testDlqTrimWithZeroDeleted() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordDlqTrim("topic1", 0, "no-op");

        // Then
        assertEquals(1, testCollector.getDlqTrimCount());
        assertEquals(0, testCollector.getLastDlqDeleted());
    }

    @Test
    void testTrimWithLargeDeletedCount() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordTrim("topic1", 0, 1_000_000, "large-trim");

        // Then
        assertEquals(1_000_000, testCollector.getLastDeleted());
    }

    @Test
    void testDifferentPartitionIds() {
        // Given
        TestRetentionMetricsCollector testCollector = new TestRetentionMetricsCollector();
        RetentionMetrics.setCollector(testCollector);

        // When
        testCollector.recordTrim("topic1", 0, 100, "maxlen");
        testCollector.recordTrim("topic1", 1, 100, "maxlen");
        testCollector.recordTrim("topic1", 2, 100, "maxlen");

        // Then
        assertEquals(3, testCollector.getTrimCount());
    }

    // Test collector implementation
    private static class TestRetentionMetricsCollector implements RetentionMetricsCollector {
        private int trimCount;
        private int dlqTrimCount;
        private String lastTopic;
        private int lastPartitionId;
        private long lastDeleted;
        private String lastReason;
        private String lastDlqTopic;
        private long lastDlqDeleted;
        private String lastDlqReason;

        @Override
        public void recordTrim(String topic, int partitionId, long deleted, String reason) {
            trimCount++;
            this.lastTopic = topic;
            this.lastPartitionId = partitionId;
            this.lastDeleted = deleted;
            this.lastReason = reason;
        }

        @Override
        public void recordDlqTrim(String topic, long deleted, String reason) {
            dlqTrimCount++;
            this.lastDlqTopic = topic;
            this.lastDlqDeleted = deleted;
            this.lastDlqReason = reason;
        }

        public int getTrimCount() { return trimCount; }
        public int getDlqTrimCount() { return dlqTrimCount; }
        public String getLastTopic() { return lastTopic; }
        public int getLastPartitionId() { return lastPartitionId; }
        public long getLastDeleted() { return lastDeleted; }
        public String getLastReason() { return lastReason; }
        public String getLastDlqTopic() { return lastDlqTopic; }
        public long getLastDlqDeleted() { return lastDlqDeleted; }
        public String getLastDlqReason() { return lastDlqReason; }
    }
}
