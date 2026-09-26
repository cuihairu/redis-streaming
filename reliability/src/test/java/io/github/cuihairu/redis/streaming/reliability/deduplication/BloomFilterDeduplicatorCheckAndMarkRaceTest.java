package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for B-19: checkAndMark used a non-atomic contains→add pair, so two
 * threads racing on the same element both got contains()==false, both returned
 * "new" and the element was processed twice — despite the interface advertising
 * checkAndMark as atomic. seenCount was a plain long, losing updates under concurrency.
 *
 * <p>The mock emulates real bloom membership (contains sees what add has written) with
 * a small delay inside contains so the check-then-act window is deterministic: on the
 * old code every racing thread still observes an empty filter and reports "new".
 */
class BloomFilterDeduplicatorCheckAndMarkRaceTest {

    private BloomFilterDeduplicator<String> newDeduplicator() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);

        Set<String> bits = ConcurrentHashMap.newKeySet();
        when(bf.contains(anyString())).thenAnswer(inv -> {
            Thread.sleep(5); // widen the contains→add window deterministically
            return bits.contains(inv.getArgument(0, String.class));
        });
        when(bf.add(anyString())).thenAnswer(inv -> bits.add(inv.getArgument(0, String.class)));

        return new BloomFilterDeduplicator<>(redisson, "bf", 1000, v -> v);
    }

    @Test
    void concurrentCheckAndMarkOfSameElementYieldsExactlyOneWinner() throws Exception {
        BloomFilterDeduplicator<String> dedup = newDeduplicator();

        int threads = 24;
        AtomicInteger newCount = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    if (!dedup.checkAndMark("msg-1")) {
                        newCount.incrementAndGet();
                    }
                    return null;
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, newCount.get(),
                "exactly one concurrent caller may see a fresh element (old code: all of them)");
        assertEquals(1L, dedup.getUniqueCount());
    }

    @Test
    void concurrentMarkAsSeenOfDistinctElementsCountsAll() throws Exception {
        BloomFilterDeduplicator<String> dedup = newDeduplicator();

        int threads = 8;
        int perThread = 250;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int base = t;
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        dedup.markAsSeen("k-" + base + "-" + i);
                    }
                    return null;
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals((long) threads * perThread, dedup.getUniqueCount(),
                "no increment may be lost under concurrency (old code: plain long seenCount++)");
    }
}
