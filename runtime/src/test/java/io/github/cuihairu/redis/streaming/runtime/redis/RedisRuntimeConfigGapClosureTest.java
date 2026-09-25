package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Residual constructor and builder branches of {@link RedisRuntimeConfig}:
 * ctor defaulting for {@code null} builder fields (defensive, reached by clearing the
 * normalized builder fields reflectively), {@code mdcSampleRate} validation arms,
 * blank-value ignoring in the string setters and {@code defaultInstanceId()} fallbacks
 * (via {@code mockStatic} on {@link SystemUtils}).
 */
class RedisRuntimeConfigGapClosureTest {

    private static void nullOut(RedisRuntimeConfig.Builder b, String field) throws Exception {
        Field f = RedisRuntimeConfig.Builder.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(b, null);
    }

    @Test
    void constructorAppliesDocumentedDefaultsForNullBuilderFields() throws Exception {
        RedisRuntimeConfig.Builder b = RedisRuntimeConfig.builder()
                .sinkDeduplicationTtl(null);
        nullOut(b, "stateTtl");
        nullOut(b, "sinkDeduplicationTtl");
        nullOut(b, "watermarkOutOfOrderness");
        nullOut(b, "windowAllowedLateness");
        nullOut(b, "checkpointInterval");
        nullOut(b, "checkpointDrainTimeout");
        RedisRuntimeConfig cfg = b.build();
        assertEquals(Duration.ZERO, cfg.getStateTtl());
        assertEquals(Duration.ofDays(7), cfg.getSinkDeduplicationTtl());
        assertEquals(Duration.ZERO, cfg.getWatermarkOutOfOrderness());
        assertEquals(Duration.ZERO, cfg.getWindowAllowedLateness());
        assertEquals(Duration.ZERO, cfg.getCheckpointInterval());
        assertEquals(Duration.ofSeconds(30), cfg.getCheckpointDrainTimeout());
    }

    @Test
    void mdcSampleRateValidationArms() {
        assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().mdcSampleRate(Double.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().mdcSampleRate(-0.5d).build());
        assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().mdcSampleRate(1.5d).build());
        assertEquals(0.0d, RedisRuntimeConfig.builder().mdcSampleRate(0.0d).build().getMdcSampleRate());
        assertEquals(1.0d, RedisRuntimeConfig.builder().mdcSampleRate(1.0d).build().getMdcSampleRate());
        assertEquals(0.5d, RedisRuntimeConfig.builder().mdcSampleRate(0.5d).build().getMdcSampleRate());
    }

    @Test
    void stringSettersIgnoreNullAndBlankValues() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(null)
                .jobName("   ")
                .jobName("kept-job")
                .jobInstanceId(" ")
                .jobInstanceId(null)
                .stateKeyPrefix(" ")
                .stateKeyPrefix(null)
                .sinkDedupKeyPrefix("")
                .sinkDedupKeyPrefix(null)
                .checkpointKeyPrefix("  ")
                .checkpointKeyPrefix(null)
                .build();
        assertEquals("kept-job", cfg.getJobName());
        assertEquals("redis-streaming-job", RedisRuntimeConfig.builder().jobName("  ").build().getJobName());
        assertTrue(cfg.getJobInstanceId() != null && !cfg.getJobInstanceId().isBlank());
        assertEquals("streaming:runtime", cfg.getStateKeyPrefix());
        assertEquals("streaming:runtime:sinkDedup:", cfg.getSinkDedupKeyPrefix());
        assertEquals("streaming:runtime:checkpoint:", cfg.getCheckpointKeyPrefix());
    }

    @Test
    void defaultInstanceIdFallsBackToLocalOnFailureAndBlankHost() {
        try (MockedStatic<SystemUtils> sys = mockStatic(SystemUtils.class)) {
            sys.when(SystemUtils::getLocalHostname).thenThrow(new IllegalStateException("host down"));
            RedisRuntimeConfig fromThrow = RedisRuntimeConfig.builder().build();
            assertEquals("local", fromThrow.getJobInstanceId());
        }
        try (MockedStatic<SystemUtils> sys = mockStatic(SystemUtils.class)) {
            sys.when(SystemUtils::getLocalHostname).thenReturn(null);
            assertEquals("local", RedisRuntimeConfig.builder().build().getJobInstanceId());
        }
        try (MockedStatic<SystemUtils> sys = mockStatic(SystemUtils.class)) {
            sys.when(SystemUtils::getLocalHostname).thenReturn("   ");
            assertEquals("local", RedisRuntimeConfig.builder().build().getJobInstanceId());
        }
        try (MockedStatic<SystemUtils> sys = mockStatic(SystemUtils.class)) {
            sys.when(SystemUtils::getLocalHostname).thenReturn("host-42");
            assertEquals("host-42", RedisRuntimeConfig.builder().build().getJobInstanceId());
        }
    }

    @Test
    void mqOptionsNullIsReplacedWithDefaults() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().mqOptions((MqOptions) null).build();
        java.util.Objects.requireNonNull(cfg.getMqOptions());
    }
}
