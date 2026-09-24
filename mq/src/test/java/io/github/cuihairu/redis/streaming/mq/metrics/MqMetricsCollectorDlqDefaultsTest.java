package io.github.cuihairu.redis.streaming.mq.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Covers the DLQ-related default methods of MqMetricsCollector.
 */
class MqMetricsCollectorDlqDefaultsTest {

    private final MqMetricsCollector collector = new MqMetricsCollector() {
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

    @Test
    void defaultDlqMethodsAreNoops() {
        assertDoesNotThrow(() -> {
            collector.recordDlqReplay("t", 1, true, 10L);
            collector.recordDlqReplay("t", 1, false, 0L);
            collector.incDlqDelete("t");
            collector.incDlqClear("t", 5);
        });
    }
}
