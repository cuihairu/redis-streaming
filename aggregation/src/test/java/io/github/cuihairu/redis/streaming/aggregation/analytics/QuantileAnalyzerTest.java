package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantileAnalyzerTest {

    @Test
    void recordWritesBothIndicesAndTracksMetric() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:lat:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:lat:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("lat", 12.5, Instant.ofEpochMilli(1000));
            verify(metrics).add("lat");
            verify(time).add(eq(1000d), anyString());
            verify(value).add(eq(12.5d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void quantileReturnsNullWhenNoSamples() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(0);
        when(value.size()).thenReturn(0);

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertNull(q.quantile("m", 0.5));
        } finally {
            q.close();
        }
    }

    @Test
    void quantileUsesEntryRangeRankAndReturnsScore() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:lat:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:lat:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);

        // no expired
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(5);
        when(entry.getScore()).thenReturn(42.0);
        when(value.entryRange(2, 2)).thenReturn(List.of(entry)); // q=0.5 => rank floor(0.5*(5-1))=2

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            Double p50 = q.p50("lat");
            assertEquals(42.0, p50);
        } finally {
            q.close();
        }
    }
}
