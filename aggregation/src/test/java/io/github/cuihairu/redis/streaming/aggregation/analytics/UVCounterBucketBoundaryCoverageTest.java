package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RHyperLogLog;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the residual {@link UVCounter} boundary branches: degenerate bucket
 * sizes fall back to one minute, unenumerable time ranges read as zero and the
 * cleanup page-drop decision for empty versus non-empty bucket indices.
 */
class UVCounterBucketBoundaryCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RSet<String> pages, RSet<String> bucketIndex, RHyperLogLog<String> hll) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getSet(anyString())).thenAnswer(inv -> {
            Object name = inv.getArgument(0);
            return String.valueOf(name).endsWith(":buckets") ? bucketIndex : pages;
        });
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        when(redisson.getKeys()).thenReturn(mock(RKeys.class));
        return redisson;
    }

    @SuppressWarnings("unchecked")
    private RSet<String> set() {
        return mock(RSet.class);
    }

    @Test
    void degenerateBucketSizesFallBackToOneMinute() {
        RSet<String> pages = set();
        RSet<String> bucketIndex = set();
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(hll.add(anyString())).thenReturn(true);
        RedissonClient redisson = redisson(pages, bucketIndex, hll);

        UVCounter zeroBucket = new UVCounter(redisson, "p", Duration.ofMinutes(5), Duration.ZERO);
        UVCounter negativeBucket = new UVCounter(redisson, "p", Duration.ofMinutes(5), Duration.ofSeconds(-3));
        try {
            zeroBucket.add("home", "u1", Instant.ofEpochMilli(125_000));
            negativeBucket.add("home", "u2", Instant.ofEpochMilli(125_000));

            // both must have collapsed to the default 60s bucket width: 120000 = (125000/60000)*60000
            verify(redisson, times(2)).getHyperLogLog("p:uv:home:120000");
        } finally {
            zeroBucket.close();
            negativeBucket.close();
        }
    }

    @Test
    void unenumerableRangeReadsAsZero() {
        RSet<String> pages = set();
        RSet<String> bucketIndex = set();
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        RedissonClient redisson = redisson(pages, bucketIndex, hll);

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(5), Duration.ofMillis(1));
        try {
            long count = counter.count("home",
                    Instant.ofEpochMilli(Long.MIN_VALUE), Instant.ofEpochMilli(Long.MAX_VALUE));
            assertEquals(0L, count, "a range that cannot be bucket-enumerated must read as 0");
        } finally {
            counter.close();
        }
    }

    @Test
    void resetSwallowsKeysBackendFailure() {
        RSet<String> pages = set();
        RSet<String> bucketIndex = set();
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getSet(anyString())).thenAnswer(inv -> {
            Object name = inv.getArgument(0);
            return String.valueOf(name).endsWith(":buckets") ? bucketIndex : pages;
        });
        when(bucketIndex.readAll()).thenReturn(Set.of("p:uv:home:1000"));
        // getKeys() is left unstubbed and returns null: the per-key delete blows up
        // and must be swallowed by the reset loop

        UVCounter counter = new UVCounter(redisson, "p", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            counter.reset("home");
            verify(bucketIndex).clear();
            verify(pages).remove("home");
        } finally {
            counter.close();
        }
    }

    @Test
    void cleanupKeepsPageWhenBucketIndexIsNotEmpty() throws Exception {
        RSet<String> pages = set();
        RSet<String> bucketIndex = set();
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(pages.readAll()).thenReturn(Set.of("home"));
        when(bucketIndex.readAll()).thenReturn(Set.of("p:uv:home:not-a-number"));
        when(bucketIndex.size()).thenReturn(2);

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p",
                Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            Method cleanup = UVCounter.class.getDeclaredMethod("cleanupExpiredData");
            cleanup.setAccessible(true);
            cleanup.invoke(counter);

            verify(pages, never()).remove("home");
        } finally {
            counter.close();
        }
    }

    @Test
    void cleanupDropsPageWhenAllBucketsExpire() throws Exception {
        RSet<String> pages = set();
        RSet<String> bucketIndex = set();
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(pages.readAll()).thenReturn(Set.of("home"));
        when(bucketIndex.readAll()).thenReturn(Set.of("p:uv:home:1"));
        when(bucketIndex.size()).thenReturn(0);

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p",
                Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            Method cleanup = UVCounter.class.getDeclaredMethod("cleanupExpiredData");
            cleanup.setAccessible(true);
            cleanup.invoke(counter);

            verify(bucketIndex).remove("p:uv:home:1");
            verify(pages).remove("home");
        } finally {
            counter.close();
        }
    }
}
