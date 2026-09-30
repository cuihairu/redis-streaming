package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the atomic checkAndMark claim (Deduplicator contract: "an atomic
 * operation for thread safety"). The old getScore→add pair let two threads both read
 * "absent" and both report a fresh element as non-duplicate; the fix claims it with
 * ZADD NX ({@code addIfAbsent}), which is atomic on the Redis side.
 */
class WindowedDeduplicatorCheckAndMarkRaceTest {

    private static final long T0 = 1_000_000L;

    /**
     * A server-side ZADD NX emulation: only the first addIfAbsent wins, getScore
     * reflects the claimed state.
     */
    @SuppressWarnings("unchecked")
    private RScoredSortedSet<String> nxEmulatingSet(AtomicBoolean claimed, AtomicLong clock) {
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        when(set.addIfAbsent(anyDouble(), anyString())).thenAnswer(inv ->
                claimed.compareAndSet(false, true));
        when(set.getScore(anyString())).thenAnswer(inv ->
                claimed.get() ? (double) clock.get() : null);
        when(set.add(anyDouble(), anyString())).thenAnswer(inv -> {
            claimed.set(true);
            return true;
        });
        when(set.removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(0);
        return set;
    }

    @Test
    void concurrentCheckAndMarkOnFreshElementAllowsExactlyOneThrough() throws Exception {
        AtomicBoolean claimed = new AtomicBoolean(false);
        AtomicLong now = new AtomicLong(T0);
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = nxEmulatingSet(claimed, now);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);

        WindowedDeduplicator<String> dedup = new WindowedDeduplicator<>(
                redisson, "s", Duration.ofSeconds(5), s -> s, now::get);

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        boolean[] fresh = new boolean[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    barrier.await();
                    fresh[idx] = !dedup.checkAndMark("a");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));

        long freshCount = 0;
        for (boolean f : fresh) {
            if (f) {
                freshCount++;
            }
        }
        assertEquals(1, freshCount,
                "exactly one racing thread may claim a fresh element; "
                        + "the other must see it as a duplicate");
    }

    @Test
    void sequentialFreshClaimUsesAddIfAbsentAndSkipsGetScore() {
        AtomicBoolean claimed = new AtomicBoolean(false);
        AtomicLong now = new AtomicLong(T0);
        RedissonClient redisson = mock(RedissonClient.class);
        RScoredSortedSet<String> set = nxEmulatingSet(claimed, now);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(set);

        WindowedDeduplicator<String> dedup = new WindowedDeduplicator<>(
                redisson, "s", Duration.ofSeconds(5), s -> s, now::get);

        assertFalse(dedup.checkAndMark("a"), "first sight is fresh");
        org.mockito.Mockito.verify(set).addIfAbsent((double) T0, "a");

        assertTrue(dedup.checkAndMark("a"), "second sight inside the window is a duplicate");
    }
}
