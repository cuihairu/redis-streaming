package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the per-element window semantics of {@link WindowedDeduplicator} (B-09):
 * entries are scored with their last-seen timestamp, expire individually, and writes
 * prune expired entries so the set stays bounded.
 */
class WindowedDeduplicatorTest {

    private static final long T0 = 1_000_000L;

    private final AtomicLong now = new AtomicLong(T0);

    @SuppressWarnings("unchecked")
    private RScoredSortedSet<String> newSet() {
        return mock(RScoredSortedSet.class);
    }

    private WindowedDeduplicator<String> newDedup(RedissonClient redisson, RScoredSortedSet<String> set) {
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);
        return new WindowedDeduplicator<>(redisson, "s", Duration.ofSeconds(5), s -> s, now::get);
    }

    @Test
    void constructorValidatesWithoutTouchingRedis() {
        RedissonClient redisson = mock(RedissonClient.class);
        new WindowedDeduplicator<>(redisson, "s", Duration.ofSeconds(5), (String v) -> v);
        verify(redisson, never()).getScoredSortedSet(anyString());

        assertThrows(IllegalArgumentException.class,
                () -> new WindowedDeduplicator<>(redisson, "s", Duration.ZERO, (String v) -> v));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowedDeduplicator<>(redisson, "s", Duration.ofMillis(-1), (String v) -> v));
        assertThrows(NullPointerException.class,
                () -> new WindowedDeduplicator<String>(null, "s", Duration.ofSeconds(5), (String v) -> v));
        assertThrows(NullPointerException.class,
                () -> new WindowedDeduplicator<>(redisson, null, Duration.ofSeconds(5), (String v) -> v));
        assertThrows(NullPointerException.class,
                () -> new WindowedDeduplicator<>(redisson, "s", null, (String v) -> v));
        assertThrows(NullPointerException.class,
                () -> new WindowedDeduplicator<>(redisson, "s", Duration.ofSeconds(5), null));
    }

    @Test
    void checkAndMarkReportsDuplicateOnlyInsideTheWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        when(set.getScore("a")).thenReturn(null);
        assertFalse(dedup.checkAndMark("a"), "first sight is not a duplicate");
        verify(set).add((double) T0, "a");
        verify(set).expire(Duration.ofSeconds(5).plus(WindowedDeduplicator.TTL_MARGIN));

        // seen 3s ago: inside the 5s window
        now.set(T0 + 3000);
        when(set.getScore("a")).thenReturn((double) T0);
        assertTrue(dedup.checkAndMark("a"), "an in-window element must be a duplicate");

        // seen 6s ago (stale, not yet pruned): outside the window
        now.set(T0 + 6000);
        when(set.getScore("a")).thenReturn((double) T0);
        assertFalse(dedup.checkAndMark("a"), "an element whose window elapsed is not a duplicate");
    }

    @Test
    void isDuplicateIgnoresStaleEntriesAndNullElements() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        when(set.getScore("fresh")).thenReturn((double) (T0 - 1000));
        when(set.getScore("stale")).thenReturn((double) (T0 - 5001));

        assertTrue(dedup.isDuplicate("fresh"));
        assertFalse(dedup.isDuplicate("stale"), "a stale entry must not be reported");
        assertFalse(dedup.isDuplicate(null));
    }

    @Test
    void writesPruneExpiredEntriesAndStampTheBackstopTtl() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        now.set(T0 + 9000); // window start = 1_004_000; everything below it is expired
        dedup.markAsSeen("b");

        verify(set).removeRangeByScore(eq(0.0), eq(true), eq(1004000.0), eq(false));
        verify(set).add((double) (T0 + 9000), "b");
        verify(set).expire(Duration.ofSeconds(5).plus(WindowedDeduplicator.TTL_MARGIN));
    }

    @Test
    void uniqueCountPrunesExpiredEntriesFirst() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        when(set.size()).thenReturn(3);
        now.set(T0 + 9000);

        assertEquals(3, dedup.getUniqueCount());
        verify(set).removeRangeByScore(eq(0.0), eq(true), eq(1004000.0), eq(false));
    }

    @Test
    void clearDeletesTheSortedSet() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        dedup.clear();
        verify(set).delete();
    }

    @Test
    void nullElementsAreNeverMarkedOrDuplicates() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        assertFalse(dedup.checkAndMark(null));
        dedup.markAsSeen(null);
        assertFalse(dedup.isDuplicate(null));
        verify(set, never()).add(anyDouble(), anyString());
    }

    @Test
    void windowDurationGetterReturnsConfiguredWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        WindowedDeduplicator<String> dedup =
                new WindowedDeduplicator<>(redisson, "s", Duration.ofSeconds(7), s -> s, now::get);
        assertEquals(Duration.ofSeconds(7), dedup.getWindowDuration());
    }

    @Test
    void elementRefreshesItsOwnWindowOnEverySight() {
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = newSet();
        WindowedDeduplicator<String> dedup = newDedup(redisson, set);

        when(set.getScore("a")).thenReturn(null);
        now.set(T0 + 3000);
        assertFalse(dedup.isDuplicate("a"));

        dedup.markAsSeen("a"); // sighted at 3000 -> score stamped at 3000
        verify(set).add((double) (T0 + 3000), "a");

        now.set(T0 + 7500);
        when(set.getScore("a")).thenReturn((double) (T0 + 3000));
        assertTrue(dedup.isDuplicate("a"), "4.5s after the refresh is still inside the 5s window");

        now.set(T0 + 8500);
        assertFalse(dedup.isDuplicate("a"), "5.5s after the refresh the window has elapsed");
    }
}
