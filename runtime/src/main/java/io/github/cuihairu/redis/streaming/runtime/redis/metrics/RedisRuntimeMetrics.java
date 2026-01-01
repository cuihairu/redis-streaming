package io.github.cuihairu.redis.streaming.runtime.redis.metrics;

/**
 * Static access to Redis runtime metrics collector. Defaults to Noop; can be overridden by integrations.
 */
public final class RedisRuntimeMetrics {
    private static volatile RedisRuntimeMetricsCollector COLLECTOR = new Noop();

    private RedisRuntimeMetrics() {
    }

    public static void setCollector(RedisRuntimeMetricsCollector c) {
        if (c != null) {
            COLLECTOR = c;
        }
    }

    public static RedisRuntimeMetricsCollector get() {
        return COLLECTOR;
    }

    private static final class Noop implements RedisRuntimeMetricsCollector {
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
