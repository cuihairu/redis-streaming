package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WindowedDeduplicator} delegate methods that were previously
 * never invoked by tests: {@code isDuplicate}, {@code clear} and
 * {@code getUniqueCount}.
 */
class WindowedDeduplicatorDelegateCoverageTest {

    @SuppressWarnings("unchecked")
    @Test
    void isDuplicateClearAndCountOperateOnTheScoredWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);

        AtomicLong now = new AtomicLong(1_000_000L);
        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(redisson, "win-dedup", Duration.ofMinutes(5), s -> s, now::get);

        when(set.getScore("k1")).thenReturn((double) (now.get() - 1000));
        when(set.getScore("k2")).thenReturn(null);
        assertTrue(dedup.isDuplicate("k1"), "seen-in-window keys must be reported as duplicates");
        assertFalse(dedup.isDuplicate("k2"), "unknown keys must not be reported as duplicates");

        when(set.size()).thenReturn(7);
        assertEquals(7, dedup.getUniqueCount(), "the count must mirror the in-window entries");
        verify(set).removeRangeByScore(eq(0.0), eq(true), anyDouble(), eq(false));

        dedup.clear();
        verify(set).delete();
    }
}
