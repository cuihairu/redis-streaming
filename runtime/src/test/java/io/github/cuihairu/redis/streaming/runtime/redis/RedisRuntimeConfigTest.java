package io.github.cuihairu.redis.streaming.runtime.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRuntimeConfigTest {

    @Test
    void defaultConfigHasSafeDefaults() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().build();

        assertTrue(cfg.getPipelineParallelism() >= 1);
        assertTrue(cfg.getTimerThreads() >= 1);
        assertTrue(cfg.getCheckpointThreads() >= 1);
        assertTrue(cfg.getWindowMaxFiresPerRecord() >= 1);
        assertTrue(cfg.getEventTimeTimerMaxSize() >= 0);
        assertNotNull(cfg.getWatermarkOutOfOrderness());
        assertNotNull(cfg.getWindowAllowedLateness());
        assertTrue(cfg.getMdcSampleRate() >= 0.0d && cfg.getMdcSampleRate() <= 1.0d);
    }

    @Test
    void watermarkOutOfOrdernessCannotBeNegative() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RedisRuntimeConfig.builder()
                .watermarkOutOfOrderness(Duration.ofMillis(-1))
                .build());
        assertTrue(e.getMessage().contains("watermarkOutOfOrderness"));
    }

    @Test
    void windowAllowedLatenessCannotBeNegative() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RedisRuntimeConfig.builder()
                .windowAllowedLateness(Duration.ofMillis(-1))
                .build());
        assertTrue(e.getMessage().contains("windowAllowedLateness"));
    }

    @Test
    void mdcSampleRateMustBeInRange() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().mdcSampleRate(-0.01d).build());
        assertTrue(e1.getMessage().contains("mdcSampleRate"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().mdcSampleRate(1.01d).build());
        assertTrue(e2.getMessage().contains("mdcSampleRate"));
    }

    @Test
    void eventTimeTimerMaxSizeCannotBeNegative() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RedisRuntimeConfig.builder()
                .eventTimeTimerMaxSize(-1)
                .build());
        assertTrue(e.getMessage().contains("eventTimeTimerMaxSize"));
    }

    @Test
    void threadsMustBePositive() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().timerThreads(0).build());
        assertTrue(e1.getMessage().contains("timerThreads"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().checkpointThreads(0).build());
        assertTrue(e2.getMessage().contains("checkpointThreads"));
    }

    @Test
    void acceptsValidRanges() {
        assertDoesNotThrow(() -> RedisRuntimeConfig.builder()
                .eventTimeTimerMaxSize(0)
                .watermarkOutOfOrderness(Duration.ZERO)
                .windowAllowedLateness(Duration.ZERO)
                .mdcSampleRate(0.0d)
                .build());
        assertDoesNotThrow(() -> RedisRuntimeConfig.builder()
                .eventTimeTimerMaxSize(1)
                .watermarkOutOfOrderness(Duration.ofSeconds(1))
                .windowAllowedLateness(Duration.ofSeconds(1))
                .mdcSampleRate(1.0d)
                .build());
    }
}
