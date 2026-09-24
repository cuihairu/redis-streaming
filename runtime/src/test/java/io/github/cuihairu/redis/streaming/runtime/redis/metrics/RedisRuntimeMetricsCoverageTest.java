package io.github.cuihairu.redis.streaming.runtime.redis.metrics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the default {@code incKeyedStateHotKey} of the collector interface and the
 * {@code Noop} collector's {@code incPipelineStartFailed} entry point.
 */
class RedisRuntimeMetricsCoverageTest {

    @Test
    void noopCollectorAcceptsPipelineStartFailure() throws Exception {
        Class<?> noopClass = Class.forName(
                "io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics$Noop");
        Constructor<?> ctor = noopClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        RedisRuntimeMetricsCollector noop = (RedisRuntimeMetricsCollector) ctor.newInstance();
        assertDoesNotThrow(() -> noop.incPipelineStartFailed("job", "topic", "group"));
    }

    @Test
    void defaultHotKeyHookIsANoop() {
        RedisRuntimeMetricsCollector collector = new RedisRuntimeMetricsCollector() {
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
            public void recordKeyedStateSize(String jobName, String topic, String consumerGroup,
                                             String operatorId, String stateName, int partitionId, long fields) {
            }
        };
        assertDoesNotThrow(() -> collector.incKeyedStateHotKey("job", "topic", "group", "op", "state", 0, 3));
    }

    @Test
    void staticAccessorsRejectNullAndReturnDefault() {
        RedisRuntimeMetricsCollector current = RedisRuntimeMetrics.get();
        assertNotNull(current);
        RedisRuntimeMetrics.setCollector(null);
        assertSame(current, RedisRuntimeMetrics.get());
    }
}
