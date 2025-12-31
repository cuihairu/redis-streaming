package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PVCounterTest {

    @Test
    void recordPageViewAddsEntryAndReturnsSize() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:home")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(sortedSet.size()).thenReturn(3);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            long out = counter.recordPageView("home", Instant.ofEpochMilli(12345));
            assertEquals(3, out);

            verify(sortedSet).add(eq(12345d), argThat(v -> v.startsWith("12345-")));
            verify(sortedSet).removeRangeByScore(eq(0d), eq(true), anyDouble(), eq(true));
            verify(sortedSet).size();
        } finally {
            counter.close();
        }
    }

    @Test
    void recordPageViewIgnoresBlankPage() {
        PVCounter counter = new PVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(10));
        try {
            assertEquals(0L, counter.recordPageView(" ", Instant.ofEpochMilli(1)));
            assertEquals(0L, counter.recordPageView(null, Instant.ofEpochMilli(1)));
        } finally {
            counter.close();
        }
    }

    @Test
    void getPageViewCountReturnsZeroForInvalidRange() {
        PVCounter counter = new PVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(10));
        try {
            assertEquals(0L, counter.getPageViewCount("home", Instant.ofEpochMilli(10), Instant.ofEpochMilli(10)));
            assertEquals(0L, counter.getPageViewCount("home", Instant.ofEpochMilli(20), Instant.ofEpochMilli(10)));
            assertEquals(0L, counter.getPageViewCount(" ", Instant.ofEpochMilli(10), Instant.ofEpochMilli(20)));
        } finally {
            counter.close();
        }
    }

    @Test
    void getPageViewCountByRangeDelegatesToCount() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:home")).thenReturn(sortedSet);
        when(sortedSet.count(1000L, true, 2000L, false)).thenReturn(5);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            long out = counter.getPageViewCount("home", Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000));
            assertEquals(5L, out);
        } finally {
            counter.close();
        }
    }

    @Test
    void resetPageViewCountClearsSortedSet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:home")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            counter.resetPageViewCount("home");
            verify(sortedSet).clear();
        } finally {
            counter.close();
        }
    }

    @Test
    void getStatisticsSumsAcrossPages() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> home = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> cart = mock(RScoredSortedSet.class);

        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(pages.readAll()).thenReturn(java.util.Set.of("home", "cart"));
        when(redisson.<String>getScoredSortedSet("p:pv:home")).thenReturn(home);
        when(redisson.<String>getScoredSortedSet("p:pv:cart")).thenReturn(cart);
        when(home.size()).thenReturn(3);
        when(cart.size()).thenReturn(2);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            PVCounter.PVStatistics s = counter.getStatistics();
            assertEquals(2L, s.getTotalPages());
            assertEquals(5L, s.getTotalViews());
            assertNotNull(s.getTimestamp());
        } finally {
            counter.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        PVCounter counter = new PVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(10));
        counter.close();
        counter.close();
        assertTrue(true);
    }

    @Test
    void recordPageViewWithDifferentTimestamps() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:product")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(sortedSet.size()).thenReturn(1);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            counter.recordPageView("product", Instant.ofEpochMilli(1000));
            counter.recordPageView("product", Instant.ofEpochMilli(2000));
            counter.recordPageView("product", Instant.ofEpochMilli(3000));

            verify(sortedSet).add(eq(1000d), argThat(v -> v.startsWith("1000-")));
            verify(sortedSet).add(eq(2000d), argThat(v -> v.startsWith("2000-")));
            verify(sortedSet).add(eq(3000d), argThat(v -> v.startsWith("3000-")));
        } finally {
            counter.close();
        }
    }

    @Test
    void recordPageViewWithZeroTimestamp() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:test")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(sortedSet.size()).thenReturn(1);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            long result = counter.recordPageView("test", Instant.ofEpochMilli(0));
            assertEquals(1, result);
            verify(sortedSet).add(eq(0d), any());
        } finally {
            counter.close();
        }
    }

    @Test
    void recordPageViewWithMaxTimestamp() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:test")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(sortedSet.size()).thenReturn(1);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            long result = counter.recordPageView("test", Instant.ofEpochMilli(Long.MAX_VALUE));
            assertEquals(1, result);
            verify(sortedSet).add(eq((double) Long.MAX_VALUE), any());
        } finally {
            counter.close();
        }
    }

    @Test
    void getPageViewCountWithSingleBucket() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:single")).thenReturn(sortedSet);
        when(sortedSet.count(0L, true, 1000L, false)).thenReturn(10);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            long count = counter.getPageViewCount("single", Instant.ofEpochMilli(0), Instant.ofEpochMilli(1000));
            assertEquals(10, count);
        } finally {
            counter.close();
        }
    }

    @Test
    void getPageViewCountWithNonExistentPage() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:nonexistent")).thenReturn(sortedSet);
        when(sortedSet.count(eq(0L), eq(true), eq(10000L), eq(false))).thenReturn(0);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            long count = counter.getPageViewCount("nonexistent", Instant.ofEpochMilli(0), Instant.ofEpochMilli(10000));
            assertEquals(0, count);
        } finally {
            counter.close();
        }
    }

    @Test
    void getPageViewCountWithEmptyPageName() {
        PVCounter counter = new PVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(10));
        try {
            assertEquals(0L, counter.getPageViewCount("", Instant.ofEpochMilli(0), Instant.ofEpochMilli(1000)));
            assertEquals(0L, counter.getPageViewCount("  ", Instant.ofEpochMilli(0), Instant.ofEpochMilli(1000)));
        } finally {
            counter.close();
        }
    }

    @Test
    void resetPageViewCountWithNonExistentPage() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:nonexistent")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            counter.resetPageViewCount("nonexistent");
            verify(sortedSet).clear();
        } finally {
            counter.close();
        }
    }

    @Test
    void getStatisticsWithEmptyPages() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(pages.readAll()).thenReturn(java.util.Set.of());

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            PVCounter.PVStatistics s = counter.getStatistics();
            assertEquals(0L, s.getTotalPages());
            assertEquals(0L, s.getTotalViews());
            assertNotNull(s.getTimestamp());
        } finally {
            counter.close();
        }
    }

    @Test
    void getStatisticsWithMultiplePages() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> page1 = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> page2 = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> page3 = mock(RScoredSortedSet.class);

        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(pages.readAll()).thenReturn(java.util.Set.of("page1", "page2", "page3"));
        when(redisson.<String>getScoredSortedSet("p:pv:page1")).thenReturn(page1);
        when(redisson.<String>getScoredSortedSet("p:pv:page2")).thenReturn(page2);
        when(redisson.<String>getScoredSortedSet("p:pv:page3")).thenReturn(page3);
        when(page1.size()).thenReturn(100);
        when(page2.size()).thenReturn(200);
        when(page3.size()).thenReturn(300);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            PVCounter.PVStatistics s = counter.getStatistics();
            assertEquals(3L, s.getTotalPages());
            assertEquals(600L, s.getTotalViews());
            assertNotNull(s.getTimestamp());
        } finally {
            counter.close();
        }
    }

    @Test
    void recordPageViewWithEmptyPageName() {
        PVCounter counter = new PVCounter(mock(RedissonClient.class), "p", Duration.ofMinutes(10));
        try {
            assertEquals(0L, counter.recordPageView("", Instant.now()));
            assertEquals(0L, counter.recordPageView("   ", Instant.now()));
        } finally {
            counter.close();
        }
    }

    @Test
    void pvStatisticsToString() {
        PVCounter.PVStatistics stats = new PVCounter.PVStatistics(10L, 1000L, Instant.ofEpochMilli(12345));
        String str = stats.toString();
        assertTrue(str.contains("10") || str.contains("1000"));
    }

    @Test
    void pvStatisticsGetters() {
        Instant timestamp = Instant.now();
        PVCounter.PVStatistics stats = new PVCounter.PVStatistics(5L, 500L, timestamp);

        assertEquals(5L, stats.getTotalPages());
        assertEquals(500L, stats.getTotalViews());
        assertEquals(timestamp, stats.getTimestamp());
    }

    @Test
    void recordPageViewWithSpecialCharacters() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RSet<String> pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet("p:pv:page-with_special.chars")).thenReturn(sortedSet);
        when(redisson.<String>getSet("p:pv:pages")).thenReturn(pages);
        when(sortedSet.size()).thenReturn(1);

        PVCounter counter = new PVCounter(redisson, "p", Duration.ofMinutes(10));
        try {
            Instant now = Instant.now();
            long result = counter.recordPageView("page-with_special.chars", now);
            assertEquals(1, result);
            verify(sortedSet).add(eq((double) now.toEpochMilli()), any(String.class));
        } finally {
            counter.close();
        }
    }
}
