package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RHyperLogLog;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for UVCounter: expire/delete failure swallows inside add()/reset(),
 * count() edge inputs (null now, negative window empty bucket set, single-bucket range)
 * and the close() forced-shutdownNow branch.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class UVCounterResidualCoverageTest {

    @Test
    void addSwallowsExpireFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(redisson.<String>getSet(anyString())).thenReturn(set);
        when(hll.add(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("expire boom")).when(hll).expire(any(Duration.class));
        doThrow(new IllegalStateException("expire boom")).when(set).expire(any(Duration.class));

        UVCounter counter = new UVCounter(redisson, "r2uv", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            assertTrue(counter.add("home", "u1", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void countTreatsNullNowAsCurrentTime() {
        RedissonClient redisson = mock(RedissonClient.class);
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(hll.count()).thenReturn(4L);
        when(hll.countWith(any(String[].class))).thenReturn(4L);

        UVCounter counter = new UVCounter(redisson, "r2uv-null", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            // null "now" falls back to Instant.now(); value depends on bucket alignment
            assertTrue(counter.count("home", (Instant) null) >= 0);
        } finally {
            counter.close();
        }
    }

    @Test
    void countWithEmptyBucketRangeReturnsZero() {
        RedissonClient redisson = mock(RedissonClient.class);
        // negative window -> firstBucket > lastBucket -> empty key set
        UVCounter counter = new UVCounter(redisson, "r2uv-neg", Duration.ofMinutes(-5), Duration.ofMinutes(1));
        try {
            assertEquals(0L, counter.count("home"));
        } finally {
            counter.close();
        }
    }

    @Test
    void countRangeWithSingleBucketUsesBaseCount() {
        RedissonClient redisson = mock(RedissonClient.class);
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(hll.count()).thenReturn(7L);

        UVCounter counter = new UVCounter(redisson, "r2uv-one", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            Instant start = Instant.ofEpochMilli(60_000);
            Instant end = Instant.ofEpochMilli(90_000); // same 1-minute bucket
            assertEquals(7L, counter.count("home", start, end));
        } finally {
            counter.close();
        }
    }

    @Test
    void resetSwallowsPerKeyDeleteFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        RSet<String> bucketIndex = mock(RSet.class);
        RSet<String> pages = mock(RSet.class);
        RKeys keys = mock(RKeys.class);
        when(redisson.<String>getSet(anyString())).thenAnswer(inv ->
                String.valueOf(inv.getArgument(0)).endsWith(":buckets") ? bucketIndex : pages);
        when(bucketIndex.readAll()).thenReturn(Set.of("r2uv:home:123"));
        when(redisson.getKeys()).thenReturn(keys);
        doThrow(new IllegalStateException("delete boom")).when(keys).delete(anyString());

        UVCounter counter = new UVCounter(redisson, "r2uv", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            assertDoesNotThrow(() -> counter.reset("home"));
        } finally {
            counter.close();
        }
    }

    @Test
    void closeForcesShutdownNowWhenTerminationTimesOut() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        RSet<String> set = mock(RSet.class);
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(redisson.<String>getSet(anyString())).thenReturn(set);

        UVCounter counter = new UVCounter(redisson, "r2uv-close", Duration.ofMinutes(5), Duration.ofMinutes(1));
        Field f = UVCounter.class.getDeclaredField("cleanupExecutor");
        f.setAccessible(true);
        ScheduledExecutorService real = (ScheduledExecutorService) f.get(counter);
        ScheduledExecutorService mockExec = mock(ScheduledExecutorService.class);
        when(mockExec.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        f.set(counter, mockExec);
        try {
            assertDoesNotThrow(counter::close);
            verify(mockExec).shutdownNow();
        } finally {
            real.shutdownNow();
        }
    }

    @Test
    void blankPageInputsAreRejected() {
        RedissonClient redisson = mock(RedissonClient.class);
        UVCounter counter = new UVCounter(redisson, "r2uv-blank", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            assertEquals(0L, counter.count(""));
            assertEquals(0L, counter.count("  "));
            assertEquals(0L, counter.count(null));
            org.junit.jupiter.api.Assertions.assertFalse(counter.add(null, "u1", Instant.now()));
            org.junit.jupiter.api.Assertions.assertFalse(counter.add("home", " ", Instant.now()));
            assertDoesNotThrow(() -> counter.reset(null));
            assertDoesNotThrow(() -> counter.reset(" "));
        } finally {
            counter.close();
        }
    }
}
