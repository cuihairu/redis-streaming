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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers UVCounter cleanup loop, bucket parsing edge cases and close/interrupt branches. */
class UVCounterCleanupCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RSet<String> pages, RSet<String> bucketIndex, RHyperLogLog<String> hll) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getSet(anyString())).thenAnswer(inv -> {
            Object name = inv.getArgument(0);
            return String.valueOf(name).endsWith(":buckets") ? bucketIndex : pages;
        });
        when(redisson.<String>getHyperLogLog(anyString())).thenReturn(hll);
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        return redisson;
    }

    @Test
    void cleanupExpiredDataRemovesOldBucketsOnly() throws Exception {
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);

        long nowBucket = (System.currentTimeMillis() / 60_000) * 60_000;
        String fresh = "p:uv:home:" + nowBucket;
        String stale = "p:uv:home:1000";
        String malformed = "p:uv:home:not-a-number";
        String noTrailing = "p:uv:home:";
        when(pages.readAll()).thenReturn(Set.of("home"));
        when(bucketIndex.readAll()).thenReturn(Set.of(fresh, stale, malformed, noTrailing));
        when(bucketIndex.size()).thenReturn(0);

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            Method cleanup = UVCounter.class.getDeclaredMethod("cleanupExpiredData");
            cleanup.setAccessible(true);
            cleanup.invoke(counter);

            verify(bucketIndex).remove(stale);
            verify(bucketIndex, never()).remove(fresh);
            verify(bucketIndex, never()).remove(malformed);
            verify(bucketIndex, never()).remove(noTrailing);
        } finally {
            counter.close();
        }
    }

    @Test
    void parseBucketStartHandlesEdgeKeys() throws Exception {
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p", Duration.ofMinutes(5), Duration.ofMinutes(1));
        try {
            Method parse = UVCounter.class.getDeclaredMethod("parseBucketStart", String.class);
            parse.setAccessible(true);
            assertNull(parse.invoke(counter, (Object) null));
            assertNull(parse.invoke(counter, "no-colon-here"));
            assertNull(parse.invoke(counter, "trailing:"));
            assertEquals(12345L, parse.invoke(counter, "prefix:12345"));
            assertNull(parse.invoke(counter, "prefix:xyz"));
        } finally {
            counter.close();
        }
    }

    @Test
    void countUsesSingleAndMultiBucketPaths() {
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(hll.count()).thenReturn(3L);
        when(hll.countWith(any(), any())).thenReturn(5L);

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p",
                Duration.ofSeconds(5), Duration.ofMinutes(1));
        try {
            assertEquals(0, counter.count(" "));
            assertEquals(0, counter.count("home", (Instant) null, Instant.now()));
            // window (5s) fits into a single minute bucket: takes the base.count() path
            assertEquals(3, counter.count("home", Instant.ofEpochMilli(125_000)));
            // multi-bucket range spanning 3 buckets: takes the countWith path
            assertEquals(5, counter.count("home", Instant.ofEpochMilli(55_000), Instant.ofEpochMilli(125_000)));
        } finally {
            counter.close();
        }
    }

    @Test
    void resetToleratesFailuresAndNullPage() {
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(bucketIndex.readAll()).thenReturn(Set.of("p:uv:x:1"));
        when(bucketIndex.size()).thenReturn(0);

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p", Duration.ofMinutes(5), Duration.ofMinutes(1));
        assertDoesNotThrow(() -> counter.reset(null));
        assertDoesNotThrow(() -> counter.reset("x"));
        verify(pages).remove("x");
    }

    @Test
    void cleanupAndCloseTolerateBackendFailureAndInterrupt() throws Exception {
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(pages.readAll()).thenThrow(new IllegalStateException("redis gone"));

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p", Duration.ofMinutes(5), Duration.ofMinutes(1));
        Method cleanup = UVCounter.class.getDeclaredMethod("cleanupExpiredData");
        cleanup.setAccessible(true);
        assertDoesNotThrow(() -> cleanup.invoke(counter));

        Thread.currentThread().interrupt();
        assertDoesNotThrow(counter::close);
        assertTrue(Thread.interrupted());
    }

    @Test
    void addStoresVisitorWithDefaultBucketSize() {
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> bucketIndex = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RHyperLogLog<String> hll = mock(RHyperLogLog.class);
        when(hll.add(anyString())).thenReturn(true);

        UVCounter counter = new UVCounter(redisson(pages, bucketIndex, hll), "p", Duration.ofMinutes(5), null);
        try {
            assertTrue(counter.add("home", "u1", Instant.now()));
            assertEquals(0, counter.count(" ", Instant.now()));
        } finally {
            counter.close();
        }
    }
}
