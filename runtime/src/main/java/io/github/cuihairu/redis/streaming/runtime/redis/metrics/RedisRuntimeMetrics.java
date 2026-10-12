package io.github.cuihairu.redis.streaming.runtime.redis.metrics;

import io.github.cuihairu.redis.streaming.api.metrics.StreamingMetricsCollector;

/**
 * Static access to Redis runtime metrics collector. Defaults to Noop; can be overridden by integrations.
 */
public final class RedisRuntimeMetrics {
    private static volatile StreamingMetricsCollector COLLECTOR = new Noop();

    private RedisRuntimeMetrics() {
    }

    public static void setCollector(StreamingMetricsCollector c) {
        if (c != null) {
            COLLECTOR = c;
        }
    }

    public static StreamingMetricsCollector get() {
        return COLLECTOR;
    }

    private static final class Noop implements StreamingMetricsCollector {
        @Override
        public void incJobStarted(String jobName) {
        }

        @Override
        public void incJobCanceled(String jobName) {
        }

        @Override
        public void incPipelineStarted(String jobName, String topic, String consumerGroup) {
        }

        @Override
        public void incPipelineStartFailed(String jobName, String topic, String consumerGroup) {
        }

        @Override
        public void incHandleSuccess(String jobName, String topic, String consumerGroup) {
        }

        @Override
        public void incHandleError(String jobName, String topic, String consumerGroup) {
        }

        @Override
        public void recordHandleLatency(String jobName, String topic, String consumerGroup, long millis) {
        }

        @Override
        public void recordKeyedStateSize(String jobName,
                                         String topic,
                                         String consumerGroup,
                                         String operatorId,
                                         String stateName,
                                         int partitionId,
                                         long fields) {
        }

        @Override
        public void incKeyedStateHotKey(String jobName,
                                        String topic,
                                        String consumerGroup,
                                        String operatorId,
                                        String stateName,
                                        int partitionId,
                                        long fields) {
        }
    }
}
