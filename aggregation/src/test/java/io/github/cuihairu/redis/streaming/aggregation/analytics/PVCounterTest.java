package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
