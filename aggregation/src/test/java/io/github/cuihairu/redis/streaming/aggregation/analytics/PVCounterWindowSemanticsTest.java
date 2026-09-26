package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-41 regression: counts and retention must follow the same trailing window
 * {@code [now - window, now]}. The old code counted the whole sorted set (a
 * future-dated event inflated the count until wall clock finally reached it) and
 * pruned by wall clock immediately after writing (an event dated before the window
 * was added only to be silently deleted again, never counted). Uses only the
 * public API, so it reproduces on the pre-fix code.
 */
class PVCounterWindowSemanticsTest {

    private RedissonClient redisson;
    private RScoredSortedSet<String> set;
    private RSet<String> pages;

    @SuppressWarnings("unchecked")
    private PVCounter newCounter() {
        redisson = mock(RedissonClient.class);
        set = mock(RScoredSortedSet.class);
        pages = mock(RSet.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);
        when(redisson.<String>getSet(anyString())).thenReturn(pages);
        return new PVCounter(redisson, "p", Duration.ofMinutes(10));
    }

    @Test
    void lateEventBeyondTheWindowIsRejectedWithoutWrite() {
        PVCounter counter = newCounter();
        try {
            when(set.count(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(7);

            // the epoch is always older than any real wall-clock window
            long out = counter.recordPageView("home", Instant.ofEpochMilli(0));

            assertEquals(7L, out, "the unchanged trailing count is returned");
            verify(set, never()).add(anyDouble(), anyString());
            verify(set, never()).removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean());
        } finally {
            counter.close();
        }
    }

    @Test
    void futureEventIsStoredButNotCounted() {
        PVCounter counter = newCounter();
        try {
            when(set.count(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(0);
            when(set.size()).thenReturn(5); // what the old code returned: size() saw the future event

            long out = counter.recordPageView("home",
                    Instant.ofEpochMilli(Long.MAX_VALUE)); // always in the future

            assertEquals(0L, out, "a future-dated event must not count until the window reaches it");
            verify(set).add(eq((double) Long.MAX_VALUE), anyString());
        } finally {
            counter.close();
        }
    }

    @Test
    void countCoversOnlyTheTrailingWindow() {
        PVCounter counter = newCounter();
        try {
            when(set.count(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(7);
            when(set.size()).thenReturn(9); // old code returned this: size() without an upper bound

            assertEquals(7L, counter.getPageViewCount("home"),
                    "the count must be bounded to [now - window, now]");
            verify(set, never()).size();
        } finally {
            counter.close();
        }
    }

    @Test
    void recordingCountsTheTrailingWindowNotTheWholeSet() {
        PVCounter counter = newCounter();
        try {
            when(set.count(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(4);
            when(set.size()).thenReturn(6);

            long out = counter.recordPageView("home", Instant.now().minusMillis(1_000));

            assertEquals(4L, out);
            verify(set, never()).size();
        } finally {
            counter.close();
        }
    }
}
