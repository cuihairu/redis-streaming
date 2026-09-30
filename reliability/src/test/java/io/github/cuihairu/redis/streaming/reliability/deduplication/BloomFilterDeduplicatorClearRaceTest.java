package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for serializing every filter touch against {@code clear()}:
 * clear() does delete() → tryInit(); an unsynchronized contains/add landing between the
 * two raised "Bloom filter is not initialized!" in an unrelated thread.
 */
class BloomFilterDeduplicatorClearRaceTest {

    @Test
    void isDuplicateDuringClearDoesNotThrow() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter(anyString())).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        when(bf.tryInit(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);

        // The mock emulates Redisson: after delete() the filter is uninitialized until
        // tryInit() runs; touching it in that window throws, like "not initialized"
        AtomicBoolean initialized = new AtomicBoolean(true);
        AtomicBoolean midClear = new AtomicBoolean(false);
        when(bf.delete()).thenAnswer(inv -> {
            midClear.set(true);
            initialized.set(false);
            Thread.sleep(150); // widen the clear window
            return true;
        });
        when(bf.tryInit(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyDouble())).thenAnswer(inv -> {
            initialized.set(true);
            midClear.set(false);
            return true;
        });
        when(bf.contains(anyString())).thenAnswer(inv -> {
            if (!initialized.get()) {
                throw new IllegalStateException("Bloom filter is not initialized!");
            }
            return false;
        });
        when(bf.add(anyString())).thenAnswer(inv -> {
            if (!initialized.get()) {
                throw new IllegalStateException("Bloom filter is not initialized!");
            }
            return true;
        });

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<>(
                redisson, "bf", 1000, 0.03, s -> s);

        CountDownLatch clearStarted = new CountDownLatch(1);
        CountDownLatch cleared = new CountDownLatch(1);
        Thread clearer = new Thread(() -> {
            try {
                clearStarted.countDown();
                dedup.clear();
            } finally {
                cleared.countDown();
            }
        });
        clearer.start();
        assertTrue(clearStarted.await(2, TimeUnit.SECONDS));

        // reader races the clear window
        assertDoesNotThrow(() -> dedup.isDuplicate("x"),
                "a reader racing clear() must not see an uninitialized filter");
        assertDoesNotThrow(() -> dedup.markAsSeen("x"),
                "a writer racing clear() must not see an uninitialized filter");

        assertTrue(cleared.await(2, TimeUnit.SECONDS));
        clearer.join(2000);
        // the filter is usable again after clear() completed
        assertTrue(initialized.get(), "clear() must re-init the filter before returning");
        assertDoesNotThrow(() -> dedup.isDuplicate("y"));
    }
}
