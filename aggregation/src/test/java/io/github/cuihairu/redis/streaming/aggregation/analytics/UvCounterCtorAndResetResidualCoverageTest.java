package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RHyperLogLog;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers the 3-arg UVCounter ctor delegation and reset/close error branches. */
class UvCounterCtorAndResetResidualCoverageTest {

    @Test
    void threeArgConstructorDelegatesToDefaultBucket() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(redisson.<String>getSet(anyString())).thenReturn(set);
        when(hll.add(anyString())).thenReturn(true);

        UVCounter counter = new UVCounter(redisson, "p3", Duration.ofMinutes(5));
        try {
            assertTrue(counter.add("home", "u1", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void resetOuterCatchIsTolerated() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(redisson.<String>getSet(anyString())).thenAnswer(inv -> {
            Object name = inv.getArgument(0);
            return String.valueOf(name).endsWith(":buckets") ? bucketIndex : pages;
        });
        when(bucketIndex.readAll()).thenThrow(new IllegalStateException("redis gone"));

        UVCounter counter = new UVCounter(redisson, "p4", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            assertDoesNotThrow(() -> counter.reset("home"));
        } finally {
            counter.close();
        }
    }
}
