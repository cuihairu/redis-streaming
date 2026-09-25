package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the residual {@link QuantileAnalyzer} branches: empty/null entry
 * ranges, swallowed cleanup failures, the empty-metric drop decision and the
 * {@code close()} forced-shutdown/interrupt paths.
 */
class QuantileAnalyzerResidualCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RScoredSortedSet<String> timeIndex,
                                    RScoredSortedSet<String> valueIndex,
                                    RSet<String> metricsIndex) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return name.endsWith(":ts") ? timeIndex : valueIndex;
        });
        when(redisson.<String>getSet(anyString())).thenReturn(metricsIndex);
        return redisson;
    }

    @SuppressWarnings("unchecked")
    private RScoredSortedSet<String> sortedSet() {
        return mock(RScoredSortedSet.class);
    }

    private ScheduledExecutorService swapExecutor(QuantileAnalyzer analyzer, ScheduledExecutorService replacement)
            throws Exception {
        Field f = QuantileAnalyzer.class.getDeclaredField("cleanupExecutor");
        f.setAccessible(true);
        ScheduledExecutorService real = (ScheduledExecutorService) f.get(analyzer);
        f.set(analyzer, replacement);
        return real;
    }

    @Test
    void quantileReturnsNullWhenEntryRangeIsEmptyOrNull() {
        RScoredSortedSet<String> timeIndex = sortedSet();
        RScoredSortedSet<String> valueIndex = sortedSet();
        @SuppressWarnings("unchecked")
        RSet<String> metricsIndex = mock(RSet.class);
        when(valueIndex.size()).thenReturn(2);
        when(valueIndex.entryRange(anyInt(), anyInt()))
                .thenReturn(List.<ScoredEntry<String>>of(), null);

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(timeIndex, valueIndex, metricsIndex),
                "p", Duration.ofMinutes(5));
        try {
            assertNull(analyzer.quantile("m", 0.5), "an empty entry range must read as no samples");
            assertNull(analyzer.quantile("m", 0.5), "a null entry range must read as no samples");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void quantileProceedsWhenTimeIndexCleanupFails() {
        RScoredSortedSet<String> valueIndex = sortedSet();
        @SuppressWarnings("unchecked")
        RSet<String> metricsIndex = mock(RSet.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            if (name.endsWith(":ts")) {
                throw new IllegalStateException("time index unavailable");
            }
            return valueIndex;
        });
        when(redisson.<String>getSet(anyString())).thenReturn(metricsIndex);

        when(valueIndex.size()).thenReturn(1);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);
        when(entry.getScore()).thenReturn(42.0);
        when(valueIndex.entryRange(anyInt(), anyInt())).thenReturn(Set.of(entry));

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(42.0, analyzer.quantile("m", 0.5), "a failing cleanup pass must not fail the query");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void cleanupSwallowsRemoveAllFailuresAndDropsEmptyMetric() throws Exception {
        RScoredSortedSet<String> timeIndex = sortedSet();
        RScoredSortedSet<String> valueIndex = sortedSet();
        @SuppressWarnings("unchecked")
        RSet<String> metricsIndex = mock(RSet.class);

        when(timeIndex.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn((Collection<String>) Set.of("expired-id"));
        doThrow(new IllegalStateException("remove failed")).when(timeIndex).removeAll(any(Collection.class));
        doThrow(new IllegalStateException("remove failed")).when(valueIndex).removeAll(any(Collection.class));
        when(timeIndex.size()).thenReturn(0);
        when(valueIndex.size()).thenReturn(0);

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(timeIndex, valueIndex, metricsIndex),
                "p", Duration.ofMinutes(5));
        try {
            assertNull(analyzer.quantile("m", 0.5));
            verify(metricsIndex).remove("m");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void cleanupKeepsMetricWhenEitherIndexStillHasData() throws Exception {
        RScoredSortedSet<String> timeIndex = sortedSet();
        RScoredSortedSet<String> valueIndex = sortedSet();
        @SuppressWarnings("unchecked")
        RSet<String> metricsIndex = mock(RSet.class);

        when(timeIndex.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(Set.of());
        when(timeIndex.size()).thenReturn(1);
        when(valueIndex.size()).thenReturn(0);

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(timeIndex, valueIndex, metricsIndex),
                "p", Duration.ofMinutes(5));
        try {
            assertNull(analyzer.quantile("m", 0.5));
            verify(metricsIndex, never()).remove(eq("m"));
        } finally {
            analyzer.close();
        }
    }

    @Test
    void cleanupSwallowsIndexLevelFailure() {
        RScoredSortedSet<String> valueIndex = sortedSet();
        when(valueIndex.size()).thenReturn(0);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(valueIndex);
        when(redisson.<String>getSet(anyString())).thenThrow(new IllegalStateException("index gone"));

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertNull(analyzer.quantile("m", 0.5),
                    "a metrics-index failure during cleanup must be swallowed and read as no samples");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void closeForcesShutdownNowWhenTerminationTimesOut() throws Exception {
        RScoredSortedSet<String> timeIndex = sortedSet();
        RScoredSortedSet<String> valueIndex = sortedSet();
        @SuppressWarnings("unchecked")
        RSet<String> metricsIndex = mock(RSet.class);
        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(timeIndex, valueIndex, metricsIndex),
                "p", Duration.ofMinutes(5));

        ScheduledExecutorService replacement = mock(ScheduledExecutorService.class);
        when(replacement.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(false);
        ScheduledExecutorService real = swapExecutor(analyzer, replacement);
        real.shutdownNow();

        analyzer.close();
        verify(replacement).shutdownNow();
    }

    @Test
    void closeRestoresInterruptFlagWhenAwaitIsInterrupted() throws Exception {
        RScoredSortedSet<String> timeIndex = sortedSet();
        RScoredSortedSet<String> valueIndex = sortedSet();
        @SuppressWarnings("unchecked")
        RSet<String> metricsIndex = mock(RSet.class);
        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(timeIndex, valueIndex, metricsIndex),
                "p", Duration.ofMinutes(5));

        ScheduledExecutorService replacement = mock(ScheduledExecutorService.class);
        when(replacement.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("stop"));
        ScheduledExecutorService real = swapExecutor(analyzer, replacement);
        real.shutdownNow();

        analyzer.close();
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored and is cleared here");
        verify(replacement).shutdownNow();
    }
}
