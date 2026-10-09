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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RT-M3: the idle watermark flush. Without it the watermark is purely record-driven, so
 * a pipeline whose source went quiet never fires its due event-time timers again. With
 * {@code watermarkIdleTimeout} configured, record silence (or a generator's markIdle)
 * flushes the watermark to MAX_VALUE — firing everything the watermark gates — and the
 * next record starts a fresh watermark epoch instead of being pinned at the flush.
 */
class RedisPipelineRunnerIdleFlushTest {

    private final RedisRuntimeMetricsCollector prev = RedisRuntimeMetrics.get();

    @AfterEach
    void restoreCollector() {
        RedisRuntimeMetrics.setCollector(prev);
    }

    @Test
    void idleFlushFiresDueEventTimeTimersAfterSilence() throws Exception {
        java.util.concurrent.CountDownLatch timerFired = new java.util.concurrent.CountDownLatch(1);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("t")
                .watermarkIdleTimeout(Duration.ofMillis(200))
                .build();

        RedisOperatorNode registersFarFutureTimer = (value, ctx, emit) -> {
            // far beyond any record-driven watermark could reach in this test
            ctx.registerEventTimeTimer(ctx.currentEventTime() + 3_600_000L, timerFired::countDown);
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg, mock(RedissonClient.class), new ObjectMapper(), "topicA", "groupA",
                List.of(registersFarFutureTimer), List.of());

        runner.handle(msgAt(System.currentTimeMillis()));

        // the idle flush fires everything the watermark gates, including this timer,
        // without any further record
        assertTrue(timerFired.await(10, TimeUnit.SECONDS),
                "idle flush should fire the due event-time timer without a further record");
        runner.close();
    }

    @Test
    void idleFlushResetsWatermarkEpochForTheNextRecord() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("t")
                .watermarkIdleTimeout(Duration.ofMillis(200))
                .build();

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg, mock(RedissonClient.class), new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of());

        long t0 = System.currentTimeMillis();
        runner.handle(msgAt(t0));
        assertEquals(t0, lastWatermark.get());

        // silence: the flush pins the watermark at MAX_VALUE
        long deadline = System.currentTimeMillis() + 10_000;
        while (lastWatermark.get() != Long.MAX_VALUE && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(Long.MAX_VALUE, lastWatermark.get(), "idle flush should raise the watermark to MAX_VALUE");

        // the next record starts a fresh epoch instead of staying pinned at MAX_VALUE
        long t1 = t0 + 5000;
        runner.handle(msgAt(t1));
        assertEquals(t1, lastWatermark.get());
        runner.close();
    }

    @Test
    void markIdleFlushesWithoutRecordSilence() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        // timeout far beyond the test duration: only markIdle can trigger the flush
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("t")
                .watermarkIdleTimeout(Duration.ofMinutes(10))
                .build();

        RedisOperatorNode declaresIdle = (value, ctx, emit) -> {
            ctx.markIdle();
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg, mock(RedissonClient.class), new ObjectMapper(), "topicA", "groupA",
                List.of(declaresIdle), List.of());

        runner.handle(msgAt(System.currentTimeMillis()));

        long deadline = System.currentTimeMillis() + 10_000;
        while (lastWatermark.get() != Long.MAX_VALUE && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(Long.MAX_VALUE, lastWatermark.get(),
                "markIdle should flush the watermark on the next sweep without record silence");
        runner.close();
    }

    @Test
    void idleFlushDisabledKeepsWatermarkRecordDriven() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("t")
                .build();

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg, mock(RedissonClient.class), new ObjectMapper(), "topicA", "groupA",
                List.of(), List.of());

        long t0 = System.currentTimeMillis();
        runner.handle(msgAt(t0));

        // no idle timeout configured: the watermark stays at the record-driven value
        Thread.sleep(400);
        assertEquals(t0, lastWatermark.get());
        runner.close();
    }

    private static Message msgAt(long epochMillis) {
        Message m = new Message();
        m.setId("id-" + epochMillis);
        m.setTimestamp(Instant.ofEpochMilli(epochMillis));
        return m;
    }

    private static final class CapturingCollector implements RedisRuntimeMetricsCollector {
        private final AtomicLong lastWatermark;

        private CapturingCollector(AtomicLong lastWatermark) {
            this.lastWatermark = lastWatermark;
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
        }

        @Override
        public void setWatermarkMs(String jobName, String topic, String consumerGroup, long watermarkMs) {
            lastWatermark.set(watermarkMs);
        }
    }
}
