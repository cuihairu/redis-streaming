package io.github.cuihairu.redis.streaming.runtime.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void leaderElectionDefaultsToDisabledWithSafeLease() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().build();
        assertTrue(!cfg.isLeaderElectionEnabled());
        assertTrue(cfg.getLeaderLeaseTtl().toMillis() > 0);
        assertTrue(cfg.getLeaderRenewInterval().toMillis() > 0);
        assertTrue(cfg.getLeaderRenewInterval().compareTo(cfg.getLeaderLeaseTtl()) < 0);
    }

    @Test
    void leaderElectionAcceptsValidLeaseConfiguration() {
        assertDoesNotThrow(() -> RedisRuntimeConfig.builder()
                .leaderElectionEnabled(true)
                .leaderLeaseTtl(Duration.ofSeconds(30))
                .leaderRenewInterval(Duration.ofSeconds(10))
                .build());
    }

    @Test
    void leaderLeaseTtlMustBePositive() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().leaderLeaseTtl(Duration.ZERO).build());
        assertTrue(e1.getMessage().contains("leaderLeaseTtl"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().leaderLeaseTtl(Duration.ofSeconds(-1)).build());
        assertTrue(e2.getMessage().contains("leaderLeaseTtl"));
    }

    @Test
    void leaderRenewIntervalMustBePositiveAndSmallerThanLease() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().leaderRenewInterval(Duration.ZERO).build());
        assertTrue(e1.getMessage().contains("leaderRenewInterval"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder()
                        .leaderLeaseTtl(Duration.ofSeconds(10))
                        .leaderRenewInterval(Duration.ofSeconds(10))
                        .build());
        assertTrue(e2.getMessage().contains("leaderRenewInterval"));
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder()
                        .leaderLeaseTtl(Duration.ofSeconds(5))
                        .leaderRenewInterval(Duration.ofSeconds(10))
                        .build());
        assertTrue(e3.getMessage().contains("leaderRenewInterval"));
    }

    @Test
    void leaderDurationsFallBackToDefaultsWhenExplicitlyNulled() {
        // the builder ships 30s/10s defaults; explicitly nulling the fields exercises the
        // constructor's default arm instead of leaving it unreachable
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .leaderLeaseTtl(null)
                .leaderRenewInterval(null)
                .build();
        assertEquals(Duration.ofSeconds(30), cfg.getLeaderLeaseTtl());
        assertEquals(Duration.ofSeconds(10), cfg.getLeaderRenewInterval());
    }
}
