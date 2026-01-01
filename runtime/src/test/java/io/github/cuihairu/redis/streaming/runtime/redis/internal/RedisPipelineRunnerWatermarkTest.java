package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RedisPipelineRunnerWatermarkTest {

    private final RedisRuntimeMetricsCollector prev = RedisRuntimeMetrics.get();

    @AfterEach
    void restoreCollector() {
        RedisRuntimeMetrics.setCollector(prev);
    }

    @Test
    void watermarkAccountsForOutOfOrderness() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark, new AtomicLong(), new AtomicLong()));

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("t")
                .watermarkOutOfOrderness(Duration.ofMillis(5))
                .build();

        RedissonClient redisson = mock(RedissonClient.class);
        ObjectMapper om = new ObjectMapper();

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg,
                redisson,
                om,
                "topicA",
                "groupA",
                List.of(),
                List.of()
        );

        runner.handle(msgAt(1000));
        assertEquals(995, lastWatermark.get());

        runner.handle(msgAt(1200));
        assertEquals(1195, lastWatermark.get());

        // out-of-order event should not decrease watermark
        runner.handle(msgAt(1100));
        assertEquals(1195, lastWatermark.get());
    }

    @Test
    void eventTimeTimerQueueIsBoundedByConfig() throws Exception {
        AtomicLong maxQueueSize = new AtomicLong(0);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(new AtomicLong(), maxQueueSize, new AtomicLong()));

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("t")
                .eventTimeTimerMaxSize(1)
                .build();

        RedissonClient redisson = mock(RedissonClient.class);
        ObjectMapper om = new ObjectMapper();

        RedisOperatorNode registersTwoTimers = (value, ctx, emit) -> {
            long t = ctx.currentEventTime() + 10_000L;
            ctx.registerEventTimeTimer(t, () -> {});
            ctx.registerEventTimeTimer(t + 1, () -> {});
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg,
                redisson,
                om,
                "topicA",
                "groupA",
                List.of(registersTwoTimers),
                List.of()
        );

        runner.handle(msgAt(1000));

        assertTrue(maxQueueSize.get() <= 1L);
    }

    @Test
    void defaultMethodsInCollectorAreCallable() {
        RedisRuntimeMetrics.get().setWatermarkMs("job", "t", "g", 123L);
        RedisRuntimeMetrics.get().incWindowLateDropped("job", "t", "g", "op", "w", 0);
        RedisRuntimeMetrics.get().incWindowFired("job", "t", "g", "op", "w", 0);
    }

    private static Message msgAt(long epochMillis) {
        Message m = new Message();
        m.setId("id-" + epochMillis);
        m.setTimestamp(Instant.ofEpochMilli(epochMillis));
        return m;
    }

    private static final class CapturingCollector implements RedisRuntimeMetricsCollector {
        private final AtomicLong lastWatermark;
        private final AtomicLong maxQueueSize;
        private final AtomicLong maxQueueObserved;

        private CapturingCollector(AtomicLong lastWatermark, AtomicLong maxQueueSize, AtomicLong maxQueueObserved) {
            this.lastWatermark = lastWatermark;
            this.maxQueueSize = maxQueueSize;
            this.maxQueueObserved = maxQueueObserved;
        }

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
        public void recordKeyedStateSize(String jobName, String topic, String consumerGroup, String operatorId, String stateName, int partitionId, long fields) {
        }

        @Override
        public void setEventTimeTimerQueueSize(String jobName, String topic, String consumerGroup, int size) {
            long s = Math.max(0L, size);
            maxQueueSize.updateAndGet(prev -> Math.max(prev, s));
            maxQueueObserved.set(s);
        }

        @Override
        public void setWatermarkMs(String jobName, String topic, String consumerGroup, long watermarkMs) {
            lastWatermark.set(watermarkMs);
        }
    }
}
