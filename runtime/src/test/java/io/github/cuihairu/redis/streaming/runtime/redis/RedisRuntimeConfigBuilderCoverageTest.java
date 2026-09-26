package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the remaining {@link RedisRuntimeConfig} builder setters, null-defaulting branches,
 * validation failures and requireNonNull guards of the constructor.
 */
class RedisRuntimeConfigBuilderCoverageTest {

    @Test
    void builderSettersRoundTripIncludingNullDefaults() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .stateTtl(Duration.ofMinutes(5))
                .stateSizeReportEveryNStateWrites(3)
                .keyedStateShardCount(4)
                .keyedStateHotKeyFieldsWarnThreshold(10)
                .keyedStateHotKeyWarnInterval(Duration.ofSeconds(30))
                .watermarkOutOfOrderness(Duration.ofMillis(7))
                .windowAllowedLateness(Duration.ofMillis(8))
                .checkpointInterval(Duration.ofMillis(9))
                .checkpointDrainTimeout(Duration.ofMillis(10))
                .build();
        assertEquals(Duration.ofMinutes(5), cfg.getStateTtl());
        assertEquals(3, cfg.getStateSizeReportEveryNStateWrites());
        assertEquals(4, cfg.getKeyedStateShardCount());
        assertEquals(10, cfg.getKeyedStateHotKeyFieldsWarnThreshold());
        assertEquals(Duration.ofSeconds(30), cfg.getKeyedStateHotKeyWarnInterval());
        assertEquals(Duration.ofMillis(7), cfg.getWatermarkOutOfOrderness());
        assertEquals(Duration.ofMillis(8), cfg.getWindowAllowedLateness());
        assertEquals(Duration.ofMillis(9), cfg.getCheckpointInterval());
        assertEquals(Duration.ofMillis(10), cfg.getCheckpointDrainTimeout());

        RedisRuntimeConfig nulled = RedisRuntimeConfig.builder()
                .stateTtl(null)
                .keyedStateHotKeyWarnInterval(null)
                .watermarkOutOfOrderness(null)
                .windowAllowedLateness(null)
                .checkpointInterval(null)
                .checkpointDrainTimeout(null)
                .mqOptions(null)
                .stateSchemaMismatchPolicy(null)
                .processingErrorResult(null)
                .build();
        assertEquals(Duration.ZERO, nulled.getStateTtl());
        assertEquals(Duration.ofMinutes(1), nulled.getKeyedStateHotKeyWarnInterval());
        assertEquals(Duration.ZERO, nulled.getWatermarkOutOfOrderness());
        assertEquals(Duration.ZERO, nulled.getWindowAllowedLateness());
        assertEquals(Duration.ZERO, nulled.getCheckpointInterval());
        assertEquals(Duration.ofSeconds(30), nulled.getCheckpointDrainTimeout());

        // Regression for RT-M1: a ZERO (or negative) drain timeout used to disable the drain
        // deadline, deadlocking the checkpoint loop with all consumers paused.
        assertEquals(Duration.ofSeconds(30), RedisRuntimeConfig.builder()
                .checkpointDrainTimeout(Duration.ZERO).build().getCheckpointDrainTimeout());
        assertEquals(Duration.ofSeconds(30), RedisRuntimeConfig.builder()
                .checkpointDrainTimeout(Duration.ofSeconds(-5)).build().getCheckpointDrainTimeout());
        assertNotNull(nulled.getMqOptions());
        assertEquals(RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL, nulled.getStateSchemaMismatchPolicy());
        assertEquals(MessageHandleResult.RETRY, nulled.getProcessingErrorResult());
    }

    @Test
    void blankStringSettersKeepDefaultsAndExplicitValuesStick() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(null)
                .jobInstanceId(" ")
                .stateKeyPrefix(null)
                .sinkDedupKeyPrefix("")
                .checkpointKeyPrefix(null)
                .build();
        assertEquals("redis-streaming-job", cfg.getJobName());
        assertEquals("streaming:runtime", cfg.getStateKeyPrefix());
        assertEquals("streaming:runtime:sinkDedup:", cfg.getSinkDedupKeyPrefix());
        assertEquals("streaming:runtime:checkpoint:", cfg.getCheckpointKeyPrefix());

        RedisRuntimeConfig explicit = RedisRuntimeConfig.builder()
                .jobName("job-x")
                .jobInstanceId("inst-x")
                .stateKeyPrefix("state-x")
                .sinkDedupKeyPrefix("dedup-x")
                .checkpointKeyPrefix("cp-x")
                .mqOptions(MqOptions.builder().claimIdleMs(1234).build())
                .processingErrorResult(MessageHandleResult.DEAD_LETTER)
                .build();
        assertEquals("job-x", explicit.getJobName());
        assertEquals("inst-x", explicit.getJobInstanceId());
        assertEquals("state-x", explicit.getStateKeyPrefix());
        assertEquals("dedup-x", explicit.getSinkDedupKeyPrefix());
        assertEquals("cp-x", explicit.getCheckpointKeyPrefix());
        assertEquals(1234, explicit.getMqOptions().getClaimIdleMs());
        assertEquals(MessageHandleResult.DEAD_LETTER, explicit.getProcessingErrorResult());
    }

    @Test
    void constructorRejectsInvalidNumericRanges() {
        assertMessage(RedisRuntimeConfig.builder().stateSizeReportEveryNStateWrites(-1),
                "stateSizeReportEveryNStateWrites");
        assertMessage(RedisRuntimeConfig.builder().keyedStateShardCount(0), "keyedStateShardCount");
        assertMessage(RedisRuntimeConfig.builder().keyedStateHotKeyFieldsWarnThreshold(-1),
                "keyedStateHotKeyFieldsWarnThreshold");
        assertMessage(RedisRuntimeConfig.builder().pipelineParallelism(0), "pipelineParallelism");
        assertMessage(RedisRuntimeConfig.builder().windowMaxFiresPerRecord(0), "windowMaxFiresPerRecord");
        assertMessage(RedisRuntimeConfig.builder().checkpointsToKeep(-1), "checkpointsToKeep");
        assertMessage(RedisRuntimeConfig.builder().timerThreads(0), "timerThreads");
        assertMessage(RedisRuntimeConfig.builder().checkpointThreads(0), "checkpointThreads");
    }

    @Test
    void constructorRejectsNullIdentityFields() throws Exception {
        assertNullFieldThrows("jobName");
        assertNullFieldThrows("jobInstanceId");
        assertNullFieldThrows("stateKeyPrefix");
        assertNullFieldThrows("sinkDedupKeyPrefix");
        assertNullFieldThrows("checkpointKeyPrefix");
    }

    @Test
    void defaultInstanceIdFallsBackToLocalWhenHostnameBlank() throws Exception {
        Field cache = Class.forName("io.github.cuihairu.redis.streaming.core.utils.SystemUtils")
                .getDeclaredField("cachedHostname");
        cache.setAccessible(true);
        Object previous = cache.get(null);
        try {
            cache.set(null, " ");
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().build();
            assertEquals("local", cfg.getJobInstanceId());
        } finally {
            cache.set(null, previous);
        }
    }

    private static void assertNullFieldThrows(String fieldName) throws Exception {
        RedisRuntimeConfig.Builder builder = RedisRuntimeConfig.builder();
        Field field = RedisRuntimeConfig.Builder.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(builder, null);
        assertThrows(NullPointerException.class, builder::build);
    }

    private static void assertMessage(RedisRuntimeConfig.Builder builder, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage().contains(fragment));
    }
}
