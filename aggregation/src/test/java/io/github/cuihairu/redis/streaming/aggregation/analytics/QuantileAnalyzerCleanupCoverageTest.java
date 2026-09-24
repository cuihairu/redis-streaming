package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers QuantileAnalyzer cleanup loops, expired-metric removal and close/interrupt branches. */
class QuantileAnalyzerCleanupCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RScoredSortedSet<String> time, RScoredSortedSet<String> value, RSet<String> metrics) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenAnswer(inv -> {
            Object name = inv.getArgument(0);
            return String.valueOf(name).endsWith(":ts") ? time : value;
        });
        when(redisson.<String>getSet(anyString())).thenReturn(metrics);
        return redisson;
    }

    @Test
    void cleanupExpiredMetricRemovesExpiredIdsAndDropsEmptyMetric() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);

        when(time.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("expired-1"));
        when(time.size()).thenReturn(0);
        when(value.size()).thenReturn(0);

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));
        try {
            Method m = QuantileAnalyzer.class.getDeclaredMethod("cleanupExpiredMetric", String.class);
            m.setAccessible(true);
            m.invoke(analyzer, "lat");

            verify(time).removeAll(List.of("expired-1"));
            verify(value).removeAll(List.of("expired-1"));
            verify(metrics).remove("lat");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void cleanupExpiredMetricKeepsLiveMetric() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);

        when(time.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of());
        when(time.size()).thenReturn(2);
        when(value.size()).thenReturn(2);

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));
        try {
            Method m = QuantileAnalyzer.class.getDeclaredMethod("cleanupExpiredMetric", String.class);
            m.setAccessible(true);
            m.invoke(analyzer, "lat");
            m.invoke(analyzer, " ");
            verify(metrics, never()).remove("lat");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void cleanupExpiredDataIteratesAllMetrics() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        Set<String> names = new java.util.LinkedHashSet<>(List.of("m1", "m2"));
        when(metrics.toArray()).thenReturn(names.toArray());
        when(time.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(1);

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));
        try {
            Method m = QuantileAnalyzer.class.getDeclaredMethod("cleanupExpiredData");
            m.setAccessible(true);
            m.invoke(analyzer);
            verify(time, times(2)).valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean());
        } finally {
            analyzer.close();
        }
    }

    @Test
    void cleanupExpiredDataToleratesIterationFailure() throws Exception {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        when(metrics.toArray()).thenThrow(new IllegalStateException("boom"));

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));
        try {
            Method m = QuantileAnalyzer.class.getDeclaredMethod("cleanupExpiredData");
            m.setAccessible(true);
            assertDoesNotThrow(() -> m.invoke(analyzer));
        } finally {
            analyzer.close();
        }
    }

    @Test
    void quantileReturnsScoreAndClampsQuery() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        when(time.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(1);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);
        when(entry.getScore()).thenReturn(42.0);
        when(value.entryRange(anyInt(), anyInt())).thenReturn(List.of(entry));

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));
        try {
            assertNull(analyzer.quantile(" ", 0.5));
            assertEquals(42.0, analyzer.quantile("lat", 0.5), 0.0001);
            assertEquals(42.0, analyzer.quantile("lat", -5), 0.0001, "q clamped to 0");
            assertEquals(42.0, analyzer.quantile("lat", 5), 0.0001, "q clamped to 1");
        } finally {
            analyzer.close();
        }
    }

    @Test
    void quantileHandlesEmptyIndexAndEmptyEntries() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        when(time.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(null);
        when(time.size()).thenReturn(3);
        when(value.size()).thenReturn(0, 3, 3);
        when(value.entryRange(anyInt(), anyInt())).thenReturn(List.of());

        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));
        try {
            assertNull(analyzer.quantile("lat", 0.5), "empty index");
            assertNull(analyzer.p50("lat"), "empty entry range");
            assertNull(analyzer.p95("lat"));
        } finally {
            analyzer.close();
        }
    }

    @Test
    void closeHandlesInterruptedShutdown() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        QuantileAnalyzer analyzer = new QuantileAnalyzer(redisson(time, value, metrics), "p", Duration.ofMinutes(5));

        Thread.currentThread().interrupt();
        assertDoesNotThrow(analyzer::close);
        assertTrue(Thread.interrupted());
    }
}
