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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void quantileClampsToUpperBound() {
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

        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(4);
        when(entry.getScore()).thenReturn(99.0);
        when(value.entryRange(3, 3)).thenReturn(List.of(entry)); // clamped to q=1 => rank 3

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(99.0, q.quantile("lat", 10.0));
        } finally {
            q.close();
        }
    }

    @Test
    void cleanupRemovesExpiredIdsAndDropsMetricWhenEmpty() throws Exception {
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

        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of("id1", "id2"));
        when(time.size()).thenReturn(0);
        when(value.size()).thenReturn(0);

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            var m = QuantileAnalyzer.class.getDeclaredMethod("cleanupExpiredMetric", String.class);
            m.setAccessible(true);
            m.invoke(q, "m");

            verify(time).removeAll(List.of("id1", "id2"));
            verify(value).removeAll(List.of("id1", "id2"));
            verify(metrics).remove("m");
        } finally {
            q.close();
        }
    }

    @Test
    void recordIgnoresBlankMetric() {
        RedissonClient redisson = mock(RedissonClient.class);
        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record(" ", 1.0, Instant.ofEpochMilli(1));
            q.record(null, 1.0, Instant.ofEpochMilli(1));
            assertTrue(true);
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithZeroTimestamp() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", 10.0, Instant.ofEpochMilli(0));
            verify(time).add(eq(0d), anyString());
            verify(value).add(eq(10.0d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithMaxTimestamp() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", 10.0, Instant.ofEpochMilli(Long.MAX_VALUE));
            verify(time).add(eq((double) Long.MAX_VALUE), anyString());
            verify(value).add(eq(10.0d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithNegativeValue() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", -10.5, Instant.ofEpochMilli(1000));
            verify(value).add(eq(-10.5d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithZeroValue() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", 0.0, Instant.ofEpochMilli(1000));
            verify(value).add(eq(0.0d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void p25Returns25thPercentile() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(10);
        when(entry.getScore()).thenReturn(25.0);
        when(value.entryRange(2, 2)).thenReturn(List.of(entry)); // q=0.25 => rank floor(0.25*(10-1))=2

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(25.0, q.quantile("m", 0.25));
        } finally {
            q.close();
        }
    }

    @Test
    void p75Returns75thPercentile() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(10);
        when(entry.getScore()).thenReturn(75.0);
        when(value.entryRange(6, 6)).thenReturn(List.of(entry)); // q=0.75 => rank floor(0.75*(10-1))=6

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(75.0, q.quantile("m", 0.75));
        } finally {
            q.close();
        }
    }

    @Test
    void p95Returns95thPercentile() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(10);
        when(entry.getScore()).thenReturn(95.0);
        when(value.entryRange(8, 8)).thenReturn(List.of(entry)); // q=0.95 => rank floor(0.95*(10-1))=8

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(95.0, q.p95("m"));
        } finally {
            q.close();
        }
    }

    @Test
    void p99Returns99thPercentile() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(100);
        when(entry.getScore()).thenReturn(99.0);
        when(value.entryRange(98, 98)).thenReturn(List.of(entry)); // q=0.99 => rank floor(0.99*(100-1))=98

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(99.0, q.p99("m"));
        } finally {
            q.close();
        }
    }

    @Test
    void quantileWithZeroPercentile() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(5);
        when(entry.getScore()).thenReturn(1.0);
        when(value.entryRange(0, 0)).thenReturn(List.of(entry)); // q=0 => rank 0

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(1.0, q.quantile("m", 0.0));
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithMultipleValues() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", 10.0, Instant.ofEpochMilli(1000));
            q.record("m", 20.0, Instant.ofEpochMilli(2000));
            q.record("m", 30.0, Instant.ofEpochMilli(3000));

            verify(value).add(eq(10.0d), anyString());
            verify(value).add(eq(20.0d), anyString());
            verify(value).add(eq(30.0d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void quantileWithSingleSample() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(1);
        when(entry.getScore()).thenReturn(42.0);
        when(value.entryRange(0, 0)).thenReturn(List.of(entry));

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(42.0, q.p50("m"));
        } finally {
            q.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        RedissonClient redisson = mock(RedissonClient.class);
        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        q.close();
        q.close();
        assertTrue(true);
    }

    @Test
    void recordWithSpecialCharactersInMetricName() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:metric-with_special.chars:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:metric-with_special.chars:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("metric-with_special.chars", 10.0, Instant.now());
            verify(time).add(anyDouble(), anyString());
            verify(value).add(anyDouble(), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void quantileWithInfinityValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(5);
        when(entry.getScore()).thenReturn(Double.POSITIVE_INFINITY);
        when(value.entryRange(2, 2)).thenReturn(List.of(entry));

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(Double.POSITIVE_INFINITY, q.p50("m"));
        } finally {
            q.close();
        }
    }

    @Test
    void quantileWithNaNValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(5);
        when(entry.getScore()).thenReturn(Double.NaN);
        when(value.entryRange(2, 2)).thenReturn(List.of(entry));

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            Double result = q.p50("m");
            assertTrue(Double.isNaN(result));
        } finally {
            q.close();
        }
    }

    @Test
    void quantileReturnsNullForBlankMetric() {
        RedissonClient redisson = mock(RedissonClient.class);
        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertNull(q.quantile("", 0.5));
            assertNull(q.quantile("  ", 0.5));
            assertNull(q.quantile(null, 0.5));
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithDefaultTimestamp() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", 10.0); // Uses Instant.now() internally
            verify(time).add(anyDouble(), anyString());
            verify(value).add(eq(10.0d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void recordWithNullTimestamp() {
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

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            q.record("m", 10.0, null); // Should use Instant.now()
            verify(time).add(anyDouble(), anyString());
            verify(value).add(eq(10.0d), anyString());
        } finally {
            q.close();
        }
    }

    @Test
    void quantileClampsToLowerBound() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> time = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> value = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> metrics = mock(RSet.class);
        @SuppressWarnings("unchecked")
        ScoredEntry<String> entry = mock(ScoredEntry.class);

        when(redisson.<String>getScoredSortedSet("p:quantile:m:ts")).thenReturn(time);
        when(redisson.<String>getScoredSortedSet("p:quantile:m:v")).thenReturn(value);
        when(redisson.<String>getSet("p:quantile:metrics")).thenReturn(metrics);
        when(time.valueRange(anyDouble(), eq(true), anyDouble(), eq(true))).thenReturn(List.of());
        when(time.size()).thenReturn(1);
        when(value.size()).thenReturn(4);
        when(entry.getScore()).thenReturn(1.0);
        when(value.entryRange(0, 0)).thenReturn(List.of(entry)); // q=-1 => clamped to 0 => rank 0

        QuantileAnalyzer q = new QuantileAnalyzer(redisson, "p", Duration.ofMinutes(5));
        try {
            assertEquals(1.0, q.quantile("m", -10.0));
        } finally {
            q.close();
        }
    }
}
