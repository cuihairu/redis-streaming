package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the residual {@link PVCounter} branches: null-timestamp recording,
 * argument-guard outcomes, the {@code close()} forced-shutdown/interrupt paths
 * and the swallowed per-page cleanup failures.
 */
class PVCounterResidualCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RScoredSortedSet<String> set, RSet<String> pages) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);
        when(redisson.<String>getSet(anyString())).thenReturn(pages);
        return redisson;
    }

    private ScheduledExecutorService swapExecutor(PVCounter counter, ScheduledExecutorService replacement)
            throws Exception {
        Field f = PVCounter.class.getDeclaredField("cleanupExecutor");
        f.setAccessible(true);
        ScheduledExecutorService real = (ScheduledExecutorService) f.get(counter);
        f.set(counter, replacement);
        return real;
    }

    @Test
    void recordPageViewWithNullTimestampFallsBackToNow() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(set.size()).thenReturn(3);

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            assertEquals(3L, counter.recordPageView("home", null));
            verify(set).add(anyDouble(), anyString());
            verify(pages).add("home");
        } finally {
            counter.close();
        }
    }

    @Test
    void invalidArgumentsShortCircuitToZeroWithoutTouchingRedis() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            assertEquals(0L, counter.getPageViewCount(null));
            assertEquals(0L, counter.getPageViewCount("  "));
            assertEquals(0L, counter.getPageViewCount(null, Instant.now(), Instant.now()));
            assertEquals(0L, counter.getPageViewCount(" ", Instant.now(), Instant.now()));
            assertEquals(0L, counter.getPageViewCount("home", null, Instant.now()));
            assertEquals(0L, counter.getPageViewCount("home", Instant.now(), null));
            Instant t = Instant.now();
            assertEquals(0L, counter.getPageViewCount("home", t, t), "an empty range must read as 0");

            counter.resetPageViewCount(null);
            counter.resetPageViewCount("");
        } finally {
            counter.close();
        }
    }

    @Test
    void rangeCountDelegatesToSortedSetCountForValidRange() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(set.count(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(2);

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            long n = counter.getPageViewCount("home", Instant.ofEpochMilli(0), Instant.ofEpochMilli(1000));
            assertEquals(2L, n);
        } finally {
            counter.close();
        }
    }

    @Test
    void closeForcesShutdownNowWhenTerminationTimesOut() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));

        ScheduledExecutorService replacement = mock(ScheduledExecutorService.class);
        when(replacement.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(false);
        ScheduledExecutorService real = swapExecutor(counter, replacement);
        real.shutdownNow();

        counter.close();
        verify(replacement).shutdownNow();
    }

    @Test
    void closeRestoresInterruptFlagWhenAwaitIsInterrupted() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));

        ScheduledExecutorService replacement = mock(ScheduledExecutorService.class);
        when(replacement.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("stop"));
        ScheduledExecutorService real = swapExecutor(counter, replacement);
        real.shutdownNow();

        counter.close();
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored and is cleared here");
        verify(replacement).shutdownNow();
    }

    @Test
    void cleanupSwallowsPerPageBackendFailures() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(pages.readAll()).thenReturn(Set.of("p1"));
        doThrow(new IllegalStateException("redis gone"))
                .when(set).removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean());
        when(set.size()).thenThrow(new IllegalStateException("redis gone"));

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            Method cleanup = PVCounter.class.getDeclaredMethod("cleanupExpiredData");
            cleanup.setAccessible(true);
            cleanup.invoke(counter);
        } finally {
            counter.close();
        }
    }

    @Test
    void statisticsAggregatePerPageCounts() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(pages.readAll()).thenReturn(Set.of("a", "b"));
        when(set.size()).thenReturn(4, 6);

        PVCounter counter = new PVCounter(redisson(set, pages), "p", Duration.ofMinutes(10));
        try {
            PVCounter.PVStatistics stats = counter.getStatistics();
            assertEquals(2, stats.getTotalPages());
            assertEquals(10, stats.getTotalViews());
        } finally {
            counter.close();
        }
    }
}
