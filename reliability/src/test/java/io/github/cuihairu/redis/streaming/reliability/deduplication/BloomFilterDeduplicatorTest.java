package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BloomFilterDeduplicatorTest {

    @Test
    void initializesBloomFilterWhenMissing() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(false);

        new BloomFilterDeduplicator<String>(redisson, "bf", 100, 0.01, (String v) -> v);

        verify(bf).tryInit(100, 0.01);
    }

    @Test
    void doesNotReinitializeWhenAlreadyExists() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);

        new BloomFilterDeduplicator<String>(redisson, "bf", 100, 0.01, (String v) -> v);

        verify(bf, never()).tryInit(anyLong(), anyDouble());
    }

    @Test
    void markAsSeenAndCheckAndMarkUpdateUniqueCountOnlyOnNewKeys() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);

        when(bf.add("a")).thenReturn(true, false);
        when(bf.contains("b")).thenReturn(true);
        when(bf.contains("c")).thenReturn(false);
        when(bf.add("c")).thenReturn(true);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        dedup.markAsSeen("a");
        dedup.markAsSeen("a");

        assertTrue(dedup.checkAndMark("b"));
        assertFalse(dedup.checkAndMark("c"));

        assertEquals(2L, dedup.getUniqueCount());
        verify(bf, times(2)).add("a");
        verify(bf).contains("b");
        verify(bf, never()).add("b");
        verify(bf).contains("c");
        verify(bf).add("c");
    }

    @Test
    void clearDeletesAndResetsCounter() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        when(bf.add("a")).thenReturn(true);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);
        dedup.markAsSeen("a");
        assertEquals(1L, dedup.getUniqueCount());

        dedup.clear();
        verify(bf).delete();
        assertEquals(0L, dedup.getUniqueCount());
    }

    @Test
    void invalidParametersAreRejected() {
        RedissonClient redisson = mock(RedissonClient.class);
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterDeduplicator<String>(redisson, "bf", 0, (String v) -> v));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterDeduplicator<String>(redisson, "bf", 1, 0.0, (String v) -> v));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilterDeduplicator<String>(redisson, "bf", 1, 1.0, (String v) -> v));
    }
}
