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

    // ===== tenant stamping (docs/Multi-Tenancy-Design.md step 3) =====

    @Test
    void tenantStampsDefaultPrefixesAndMqOptions() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("counter")
                .tenant("acme")
                .build();

        assertEquals("streaming:runtime:acme", cfg.getStateKeyPrefix());
        assertEquals("streaming:runtime:checkpoint:acme:", cfg.getCheckpointKeyPrefix());
        assertEquals("streaming:runtime:sinkDedup:acme:", cfg.getSinkDedupKeyPrefix());
        assertEquals("acme", cfg.getMqOptions().getTenant());
    }

    @Test
    void defaultTenantIsANoop() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .tenant("default")
                .tenant(null)
                .tenant("  ")
                .build();

        assertEquals("streaming:runtime", cfg.getStateKeyPrefix());
        assertEquals("streaming:runtime:checkpoint:", cfg.getCheckpointKeyPrefix());
        assertEquals("default", cfg.getMqOptions().getTenant());
    }

    @Test
    void customizedPrefixesWinOverTenantStamp() {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .stateKeyPrefix("my:state")
                .checkpointKeyPrefix("my:ckpt:")
                .sinkDedupKeyPrefix("my:dedup:")
                .tenant("acme")
                .build();

        assertEquals("my:state", cfg.getStateKeyPrefix());
        assertEquals("my:ckpt:", cfg.getCheckpointKeyPrefix());
        assertEquals("my:dedup:", cfg.getSinkDedupKeyPrefix());
    }

    @Test
    void explicitMqTenantIsNotOverridden() {
        io.github.cuihairu.redis.streaming.mq.config.MqOptions mq =
                io.github.cuihairu.redis.streaming.mq.config.MqOptions.builder().tenant("acme").build();
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .mqOptions(mq)
                .tenant("billing")
                .build();

        assertEquals("acme", cfg.getMqOptions().getTenant());
        // prefixes still carry the spec tenant
        assertEquals("streaming:runtime:billing", cfg.getStateKeyPrefix());
    }

    @Test
    void tenantStampPreservesMqOptionsSettings() {
        io.github.cuihairu.redis.streaming.mq.config.MqOptions mq =
                io.github.cuihairu.redis.streaming.mq.config.MqOptions.builder()
                        .keyPrefix("custom:mq")
                        .defaultPartitionCount(4)
                        .build();
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .mqOptions(mq)
                .tenant("acme")
                .build();

        assertEquals("acme", cfg.getMqOptions().getTenant());
        assertEquals("custom:mq", cfg.getMqOptions().getKeyPrefix());
        assertEquals(4, cfg.getMqOptions().getDefaultPartitionCount());
        // original options untouched
        assertEquals("default", mq.getTenant());
    }

    @Test
    void tenantRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class,
                () -> RedisRuntimeConfig.builder().tenant("bad:name").build());
    }
}
