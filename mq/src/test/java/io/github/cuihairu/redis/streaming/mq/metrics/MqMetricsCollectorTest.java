package io.github.cuihairu.redis.streaming.mq.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MqMetricsCollector interface
 */
class MqMetricsCollectorTest {

    @Test
    void testInterfaceHasIncProducedMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("incProduced", String.class, int.class));
    }

    @Test
    void testInterfaceHasIncConsumedMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("incConsumed", String.class, int.class));
    }

    @Test
    void testInterfaceHasIncAckedMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("incAcked", String.class, int.class));
    }

    @Test
    void testInterfaceHasIncRetriedMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("incRetried", String.class, int.class));
    }

    @Test
    void testInterfaceHasIncDeadLetterMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("incDeadLetter", String.class, int.class));
    }

    @Test
    void testInterfaceHasRecordHandleLatencyMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("recordHandleLatency", String.class, int.class, long.class));
    }

    @Test
    void testInterfaceHasIncPayloadMissingMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("incPayloadMissing", String.class, int.class));
    }

    @Test
    void testInterfaceHasSetInFlightMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("setInFlight", String.class, long.class, int.class));
    }

    @Test
    void testInterfaceHasRecordBackpressureWaitMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("recordBackpressureWait", String.class, long.class));
    }

    @Test
    void testInterfaceHasSetEligiblePartitionsMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("setEligiblePartitions", String.class, String.class, String.class, int.class));
    }

    @Test
    void testInterfaceHasSetLeasedPartitionsMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("setLeasedPartitions", String.class, String.class, String.class, int.class));
    }

    @Test
    void testInterfaceHasSetMaxLeasedPartitionsMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MqMetricsCollector.class.getMethod("setMaxLeasedPartitions", String.class, int.class));
    }

    @Test
    void testSimpleImplementation() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - should not throw
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
    void testDefaultIncPayloadMissingDoesNotThrow() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - default implementation should not throw
        assertDoesNotThrow(() -> collector.incPayloadMissing("test-topic", 0));
    }

    @Test
    void testDefaultSetInFlightDoesNotThrow() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - default implementation should not throw
        assertDoesNotThrow(() -> collector.setInFlight("consumer-1", 10, 100));
    }

    @Test
    void testDefaultRecordBackpressureWaitDoesNotThrow() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - default implementation should not throw
        assertDoesNotThrow(() -> collector.recordBackpressureWait("consumer-1", 50));
    }

    @Test
    void testDefaultSetEligiblePartitionsDoesNotThrow() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - default implementation should not throw
        assertDoesNotThrow(() -> collector.setEligiblePartitions("consumer-1", "test-topic", "group-1", 5));
    }

    @Test
    void testDefaultSetLeasedPartitionsDoesNotThrow() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - default implementation should not throw
        assertDoesNotThrow(() -> collector.setLeasedPartitions("consumer-1", "test-topic", "group-1", 3));
    }

    @Test
    void testDefaultSetMaxLeasedPartitionsDoesNotThrow() {
        // Given
        MqMetricsCollector collector = new MqMetricsCollector() {
            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }
        };

        // When & Then - default implementation should not throw
        assertDoesNotThrow(() -> collector.setMaxLeasedPartitions("consumer-1", 10));
    }

    @Test
    void testImplementationWithCounter() {
        // Given - implementation that actually counts
        class CountingCollector implements MqMetricsCollector {
            private int produced = 0;
            private int consumed = 0;
            private int acked = 0;
            private int retried = 0;
            private int deadLetter = 0;

            @Override
            public void incProduced(String topic, int partitionId) {
                produced++;
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
                consumed++;
            }

            @Override
            public void incAcked(String topic, int partitionId) {
                acked++;
            }

            @Override
            public void incRetried(String topic, int partitionId) {
                retried++;
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
                deadLetter++;
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
            }

            public int getProduced() {
                return produced;
            }

            public int getConsumed() {
                return consumed;
            }

            public int getAcked() {
                return acked;
            }

            public int getRetried() {
                return retried;
            }

            public int getDeadLetter() {
                return deadLetter;
            }
        }

        CountingCollector collector = new CountingCollector();

        // When
        collector.incProduced("topic1", 0);
        collector.incProduced("topic1", 0);
        collector.incProduced("topic2", 0);
        collector.incConsumed("topic1", 0);
        collector.incAcked("topic1", 0);
        collector.incAcked("topic1", 0);
        collector.incRetried("topic1", 0);
        collector.incDeadLetter("topic1", 0);

        // Then
        assertEquals(3, collector.getProduced());
        assertEquals(1, collector.getConsumed());
        assertEquals(2, collector.getAcked());
        assertEquals(1, collector.getRetried());
        assertEquals(1, collector.getDeadLetter());
    }

    @Test
    void testImplementationWithLatencyTracking() {
        // Given - implementation that tracks latencies
        class LatencyTrackingCollector implements MqMetricsCollector {
            private long totalLatency = 0;
            private int count = 0;

            @Override
            public void incProduced(String topic, int partitionId) {
            }

            @Override
            public void incConsumed(String topic, int partitionId) {
            }

            @Override
            public void incAcked(String topic, int partitionId) {
            }

            @Override
            public void incRetried(String topic, int partitionId) {
            }

            @Override
            public void incDeadLetter(String topic, int partitionId) {
            }

            @Override
            public void recordHandleLatency(String topic, int partitionId, long millis) {
                totalLatency += millis;
                count++;
            }

            public double getAverageLatency() {
                return count == 0 ? 0 : (double) totalLatency / count;
            }
        }

        LatencyTrackingCollector collector = new LatencyTrackingCollector();

        // When
        collector.recordHandleLatency("topic1", 0, 100);
        collector.recordHandleLatency("topic1", 0, 200);
        collector.recordHandleLatency("topic1", 0, 300);

        // Then - (100 + 200 + 300) / 3 = 200
        assertEquals(200.0, collector.getAverageLatency(), 0.01);
    }
}
