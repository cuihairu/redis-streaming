package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers PVCounter cleanup loop, statistics aggregation and close/interrupt branches. */
class PVCounterCleanupCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RScoredSortedSet<String> set, RSet<String> pages) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);
        when(redisson.<String>getSet(anyString())).thenReturn(pages);
        return redisson;
    }

    @Test
    void cleanupExpiredDataTrimsAndDropsEmptyPages() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(pages.readAll()).thenReturn(new LinkedHashSet<>(List.of("p1", "p2")));
        when(set.size()).thenReturn(0, 5);

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            Method cleanup = PVCounter.class.getDeclaredMethod("cleanupExpiredData");
            cleanup.setAccessible(true);
            cleanup.invoke(counter);

            verify(set, times(2)).removeRangeByScore(anyDouble(), org.mockito.ArgumentMatchers.anyBoolean(), anyDouble(), org.mockito.ArgumentMatchers.anyBoolean());
            verify(pages).remove("p1");
            verify(pages, never()).remove("p2");
        } finally {
            counter.close();
        }
    }

    @Test
    void cleanupExpiredDataSwallowsBackendFailures() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(pages.readAll()).thenThrow(new IllegalStateException("redis gone"));

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            Method cleanup = PVCounter.class.getDeclaredMethod("cleanupExpiredData");
            cleanup.setAccessible(true);
            assertDoesNotThrow(() -> cleanup.invoke(counter));
        } finally {
            counter.close();
        }
    }

    @Test
    void getStatisticsAggregatesPagesAndToleratesPageFailure() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(pages.readAll()).thenReturn(new LinkedHashSet<>(List.of("ok", "bad")));
        when(set.size()).thenReturn(4).thenThrow(new IllegalStateException("boom"));

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            PVCounter.PVStatistics stats = counter.getStatistics();
            assertNotNull(stats);
            assertEquals(2, stats.getTotalPages());
            assertEquals(4, stats.getTotalViews(), "failing page is skipped");
            assertNotNull(stats.getTimestamp());
        } finally {
            counter.close();
        }
    }

    @Test
    void closeHandlesInterruptedShutdown() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));

        Thread.currentThread().interrupt();
        assertDoesNotThrow(counter::close);
        assertTrue(Thread.interrupted(), "interrupt flag preserved then cleared");
    }

    @Test
    void recordAndCountEdgeInputTolerated() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            assertEquals(0, counter.recordPageView(null, Instant.now()));
            assertEquals(0, counter.recordPageView(" "));
            assertEquals(0, counter.getPageViewCount(" "));
            assertEquals(0, counter.getPageViewCount("x", null, Instant.now()));
            assertEquals(0, counter.getPageViewCount("x", Instant.now(), Instant.now()));
            counter.resetPageViewCount(null);
            counter.resetPageViewCount("x");
        } finally {
            counter.close();
        }
    }
}
