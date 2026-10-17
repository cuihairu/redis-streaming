package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.metrics.StreamingMetricsCollector;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Phase 3 leg 2: event-time watermark alignment across a dual-source pipeline.
 *
 * <p>The pipeline watermark is the <em>minimum</em> over the per-source candidates, so a window
 * may only close once every source has advanced past it. That is what makes a dual-source join
 * sound: the fast side's rows cannot close their windows while the slow side still owes an
 * in-window peer. The tests below pin both halves — the alignment itself, and the RT-M3 idle
 * flush that unblocks a source that goes permanently quiet (otherwise the conservative minimum
 * would stall the pipeline forever).
 *
 * <p>Single-source behavior must be untouched by the alignment, so the first test pins it.
 */
class RedisPipelineRunnerDualSourceWatermarkTest {

    private static final String TOPIC_L = "topicL";
    private static final String TOPIC_R = "topicR";

    private final StreamingMetricsCollector prev = RedisRuntimeMetrics.get();

    @AfterEach
    void restoreCollector() {
        RedisRuntimeMetrics.setCollector(prev);
    }

    private static RedisPipelineRunner<Object> dualRunner(RedisRuntimeConfig cfg,
                                                         List<RedisOperatorNode> operators) {
        return new RedisPipelineRunner<>(cfg, mock(RedissonClient.class), new ObjectMapper(),
                List.of(TOPIC_L, TOPIC_R), List.of("groupL", "groupR"), operators, List.of(),
                null, true, false);
    }

    private static Message msg(String topic, long epochMillis) {
        Message m = new Message();
        m.setId(topic + "-" + epochMillis);
        m.setTopic(topic);
        m.setTimestamp(Instant.ofEpochMilli(epochMillis));
        return m;
    }

    /** Spin until {@code cond} holds or the budget runs out; returns the final value. */
    private static long awaitWatermark(AtomicLong observed, long expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (observed.get() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return observed.get();
    }

    // ---------- single source is unchanged ----------

    @Test
    void aSingleSourcePipelineStillAdvancesOnItsOwn() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                RedisRuntimeConfig.builder().jobName("single").build(),
                mock(RedissonClient.class), new ObjectMapper(), TOPIC_L, "groupL", List.of(), List.of());
        try {
            runner.handle(msg(TOPIC_L, 1000));
            // ooo = 0 -> watermark = max event time - 1; the single source is the whole pipeline
            assertEquals(999L, lastWatermark.get());
        } finally {
            runner.close();
        }
    }

    // ---------- the aligned minimum ----------

    @Test
    void aSilentSourceHoldsTheWatermarkBack() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("aligned").build(), List.of());
        try {
            // the left side races far ahead on its own...
            runner.handle(msg(TOPIC_L, 500_000), 0);
            // ...and must not advance the pipeline watermark: the right source has not spoken yet,
            // so it still owes a record that could pair with what the left side already buffered
            assertEquals(Long.MIN_VALUE, lastWatermark.get(),
                    "a source that never delivered must block the aligned watermark");

            // the right side speaks; now the minimum is the left side's candidate
            runner.handle(msg(TOPIC_R, 1000), 1);
            assertEquals(999L, lastWatermark.get());
        } finally {
            runner.close();
        }
    }

    @Test
    void theAlignedWatermarkFollowsTheSlowerSource() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisPipelineRunner<Object> runner = dualRunner(RedisRuntimeConfig.builder()
                .jobName("min")
                .watermarkOutOfOrderness(Duration.ofMillis(5))
                .build(), List.of());
        try {
            runner.handle(msg(TOPIC_L, 5000), 0);
            runner.handle(msg(TOPIC_R, 2000), 1);
            // min(5000 - 5 - 1, 2000 - 5 - 1) = 1994
            assertEquals(1994L, lastWatermark.get());

            // the fast source advancing does not move the aligned watermark: the slow side still
            // holds it back
            runner.handle(msg(TOPIC_L, 8000), 0);
            assertEquals(1994L, lastWatermark.get(), "the right side is still the minimum");

            // only when the slow source advances does the aligned watermark follow
            runner.handle(msg(TOPIC_R, 3000), 1);
            assertEquals(2994L, lastWatermark.get());
        } finally {
            runner.close();
        }
    }

    @Test
    void theAlignedWatermarkNeverGoesBackwards() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("mono").build(), List.of());
        try {
            runner.handle(msg(TOPIC_L, 9000), 0);
            runner.handle(msg(TOPIC_R, 9000), 1);
            assertEquals(8999L, lastWatermark.get());

            // a late record on the fast source lowers its own candidate, but the published
            // watermark stays at the high-water mark
            runner.handle(msg(TOPIC_L, 1000), 0);
            assertEquals(8999L, lastWatermark.get());
        } finally {
            runner.close();
        }
    }

    // ---------- the join-facing consequence: close timers ----------

    @Test
    void aCloseTimerFiresOnlyAfterEverySourceHasPassedIt() throws Exception {
        List<String> fired = new CopyOnWriteArrayList<>();

        // the join's waiting-mode close: the left half owes an unmatched row 1000ms after its
        // event time, and the right half's closes are symmetric
        RedisOperatorNode joinClose = (value, ctx, emit) -> {
            if (TOPIC_L.equals(ctx.currentTopic())) {
                ctx.registerEventTimeTimer(ctx.currentEventTime() + 1000L, () -> fired.add("closed"));
            }
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("timers").build(), List.of(joinClose));
        try {
            runner.handle(msg(TOPIC_L, 1000), 0);   // close owed at 2000
            assertEquals(List.of(), fired, "the right source has not spoken, so nothing may close");

            runner.handle(msg(TOPIC_R, 1500), 1);  // in-window partner; watermark 1499
            assertEquals(List.of(), fired);

            runner.handle(msg(TOPIC_L, 2000), 0);   // watermark min(1999, 1499) = 1499
            runner.handle(msg(TOPIC_R, 2500), 1);   // right passed 2000; watermark min(1999, 2499) = 1999
            assertEquals(List.of(), fired,
                    "the right source is past the close time but the left one is not — a close may"
                            + " only fire once every side has advanced past it");

            runner.handle(msg(TOPIC_L, 2200), 0);   // watermark min(2199, 2499) = 2199 >= 2000
            assertEquals(List.of("closed"), fired);
        } finally {
            runner.close();
        }
    }

    @Test
    void aGeneratorRaiseOnlyCountsForItsOwnSource() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisOperatorNode eagerGenerator = (value, ctx, emit) -> {
            ctx.raiseWatermark(ctx.currentEventTime() + 60_000L);
            emit.emit(value);
        };
        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("gen").build(), List.of(eagerGenerator));
        try {
            runner.handle(msg(TOPIC_L, 1000), 0);
            assertEquals(Long.MIN_VALUE, lastWatermark.get(),
                    "the left generator's raise must not open the pipeline while the right source is silent");

            // both sources have raised now; the aligned watermark is the lower of the two
            runner.handle(msg(TOPIC_R, 2000), 1);
            assertEquals(61000L, lastWatermark.get(), "min(left 61000, right 62000) = 61000");
        } finally {
            runner.close();
        }
    }

    // ---------- source resolution ----------

    @Test
    void sourceIndexOfResolvesTheArrivingTopic() throws Exception {
        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("resolve").build(), List.of());
        try {
            assertEquals(0, runner.sourceIndexOf(msg(TOPIC_L, 1)));
            assertEquals(1, runner.sourceIndexOf(msg(TOPIC_R, 1)));
            assertEquals(-1, runner.sourceIndexOf(msg("someOtherTopic", 1)), "not one of ours");
            assertEquals(-1, runner.sourceIndexOf(null));
        } finally {
            runner.close();
        }
    }

    @Test
    void theContextReportsTheArrivingSourceToOperators() throws Exception {
        List<String> seen = new ArrayList<>();

        RedisOperatorNode probe = (value, ctx, emit) -> {
            seen.add(ctx.currentSourceIndex() + ":" + ctx.currentTopic() + ":" + ctx.currentConsumerGroup());
            emit.emit(value);
        };
        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("ctx").build(), List.of(probe));
        try {
            runner.handle(msg(TOPIC_L, 1000), 0);
            runner.handle(msg(TOPIC_R, 2000), 1);
            runner.handle(msg(TOPIC_L, 3000), 0);

            assertEquals(List.of("0:topicL:groupL", "1:topicR:groupR", "0:topicL:groupL"), seen);
        } finally {
            runner.close();
        }
    }

    @Test
    void anOutOfRangeSourceIndexFallsBackToThePrimarySource() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));
        List<String> seen = new ArrayList<>();

        RedisOperatorNode probe = (value, ctx, emit) -> {
            seen.add(String.valueOf(ctx.currentSourceIndex()));
            emit.emit(value);
        };
        RedisPipelineRunner<Object> runner = dualRunner(
                RedisRuntimeConfig.builder().jobName("fallback").build(), List.of(probe));
        try {
            runner.handle(msg(TOPIC_L, 1000), 7);
            runner.handle(msg(TOPIC_R, 2000), -3);

            assertEquals(List.of("0", "0"), seen, "an unresolvable source must not invent a third source");
            assertEquals(Long.MIN_VALUE, lastWatermark.get(),
                    "both records landed on source 0, so the right source stayed silent");
        } finally {
            runner.close();
        }
    }

    // ---------- the escape hatch ----------

    @Test
    void anIdleFlushUnblocksASourceThatWentPermanentlyQuiet() throws Exception {
        List<String> fired = new CopyOnWriteArrayList<>();
        RedisOperatorNode joinClose = (value, ctx, emit) -> {
            ctx.registerEventTimeTimer(ctx.currentEventTime() + 1000L, () -> fired.add("closed"));
            emit.emit(value);
        };

        RedisPipelineRunner<Object> runner = dualRunner(RedisRuntimeConfig.builder()
                .jobName("idle")
                .watermarkIdleTimeout(Duration.ofMillis(200))
                .build(), List.of(joinClose));
        try {
            runner.handle(msg(TOPIC_L, System.currentTimeMillis()), 0);
            // the right source never delivers; without the idle flush the close could never fire
            assertEquals(List.of(), fired);

            long deadline = System.currentTimeMillis() + 10_000;
            while (fired.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(List.of("closed"), fired,
                    "the RT-M3 sweep must unblock a permanently quiet source");
        } finally {
            runner.close();
        }
    }

    @Test
    void aFreshEpochAfterAnIdleFlushStillNeedsEverySource() throws Exception {
        AtomicLong lastWatermark = new AtomicLong(Long.MIN_VALUE);
        RedisRuntimeMetrics.setCollector(new CapturingCollector(lastWatermark));

        RedisPipelineRunner<Object> runner = dualRunner(RedisRuntimeConfig.builder()
                .jobName("epoch")
                .watermarkIdleTimeout(Duration.ofMillis(200))
                .build(), List.of());
        try {
            long t0 = System.currentTimeMillis();
            runner.handle(msg(TOPIC_L, t0), 0);
            assertEquals(Long.MAX_VALUE,
                    awaitWatermark(lastWatermark, Long.MAX_VALUE, 10_000), "idle flush");

            // the flush voided every source's promise, so the next record restarts the epoch with
            // the quiet source unseeded
            runner.handle(msg(TOPIC_L, t0 + 5000), 0);
            assertEquals(Long.MIN_VALUE, lastWatermark.get(),
                    "the quiet source must re-block the watermark in the new epoch");

            runner.handle(msg(TOPIC_R, t0 + 6000), 1);
            assertEquals(t0 + 4999L, lastWatermark.get(),
                    "min(left " + (t0 + 4999) + ", right " + (t0 + 5999) + ")");
        } finally {
            runner.close();
        }
    }

    // ---------- shared timer budget and guards ----------

    @Test
    void theTimerQueueIsBoundedAcrossSources() throws Exception {
        RedisOperatorNode registerOne = (value, ctx, emit) -> {
            ctx.registerEventTimeTimer(ctx.currentEventTime() + 10_000L, () -> {
            });
            emit.emit(value);
        };
        RedisPipelineRunner<Object> runner = dualRunner(RedisRuntimeConfig.builder()
                .jobName("bounded")
                .eventTimeTimerMaxSize(2)
                .build(), List.of(registerOne));
        try {
            // two registrations from source 0 fill the shared queue...
            runner.handle(msg(TOPIC_L, 1000), 0);
            runner.handle(msg(TOPIC_L, 1100), 0);
            // ...and the bound is on the pipeline's queue, not per source
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> runner.handle(msg(TOPIC_R, 2000), 1));
            assertTrue(e.getMessage().contains("eventTimeTimerMaxSize"), e.getMessage());
        } finally {
            runner.close();
        }
    }

    @Test
    void theDualSourceConstructorRejectsAnEmptyOrRaggedSourceList() {
        RedissonClient redisson = mock(RedissonClient.class);
        ObjectMapper om = new ObjectMapper();
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().jobName("guard").build();

        assertThrows(IllegalArgumentException.class, () -> new RedisPipelineRunner<>(
                cfg, redisson, om, List.of(), List.of(), List.of(), List.of(), null, true, false));

        IllegalArgumentException ragged = assertThrows(IllegalArgumentException.class,
                () -> new RedisPipelineRunner<>(cfg, redisson, om, List.of(TOPIC_L, TOPIC_R), List.of("groupL"),
                        List.of(), List.of(), null, true, false));
        assertTrue(ragged.getMessage().contains("same size"), ragged.getMessage());
    }

    private static final class CapturingCollector implements StreamingMetricsCollector {
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
        public void recordKeyedStateSize(String jobName, String topic, String consumerGroup, String operatorId,
                                        String stateName, int partitionId, long fields) {
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