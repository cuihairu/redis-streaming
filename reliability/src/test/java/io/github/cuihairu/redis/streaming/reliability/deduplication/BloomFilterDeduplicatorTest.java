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

    @Test
    void nullElementHandling() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        // Null elements should never be considered duplicates
        assertFalse(dedup.isDuplicate(null));

        // Null elements should not affect the filter
        dedup.markAsSeen(null);
        verify(bf, never()).add(anyString());

        // checkAndMark should return false for null
        assertFalse(dedup.checkAndMark(null));
    }

    @Test
    void isDuplicateDoesNotModifyFilter() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        when(bf.contains("key")).thenReturn(false);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        // isDuplicate should only check, not add
        assertFalse(dedup.isDuplicate("key"));
        verify(bf).contains("key");
        verify(bf, never()).add("key");
    }

    @Test
    void countReturnsApproximateSize() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        when(bf.count()).thenReturn(42L);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        assertEquals(42L, dedup.count());
    }

    @Test
    void containsKeyDelegatesToBloomFilter() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        when(bf.contains("test-key")).thenReturn(true);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        assertTrue(dedup.containsKey("test-key"));
        verify(bf).contains("test-key");
    }

    @Test
    void defaultConstructorWithDefaultFalseProbability() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(false);

        new BloomFilterDeduplicator<String>(redisson, "bf", 100, (String v) -> v);

        // Should use default false probability of 0.03
        verify(bf).tryInit(100, 0.03);
    }

    @Test
    void getExpectedFalseProbability() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        when(bf.getExpectedInsertions()).thenReturn(1000L);
        when(bf.getFalseProbability()).thenReturn(0.01);

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        assertEquals(0.01, dedup.getExpectedFalseProbability(), 0.001);
    }

    @Test
    void uniqueCountTracksNewAdditionsOnly() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);

        // Simulate bloom filter behavior: add returns true only for new elements
        // For each key, define the behavior for each call
        when(bf.add("key1")).thenReturn(true).thenReturn(false); // First call: new, second call: exists
        when(bf.add("key2")).thenReturn(true); // New
        when(bf.add("key3")).thenReturn(false); // Already exists

        BloomFilterDeduplicator<String> dedup = new BloomFilterDeduplicator<String>(redisson, "bf", 10, (String v) -> v);

        assertEquals(0L, dedup.getUniqueCount());

        dedup.markAsSeen("key1");
        assertEquals(1L, dedup.getUniqueCount());

        dedup.markAsSeen("key2");
        assertEquals(2L, dedup.getUniqueCount());

        dedup.markAsSeen("key3"); // Already exists
        assertEquals(2L, dedup.getUniqueCount());

        dedup.markAsSeen("key1"); // Already exists
        assertEquals(2L, dedup.getUniqueCount());
    }

    @Test
    void nullParametersAreRejected() {
        RedissonClient redisson = mock(RedissonClient.class);

        assertThrows(NullPointerException.class,
                () -> new BloomFilterDeduplicator<String>(null, "bf", 10, (String v) -> v));

        assertThrows(NullPointerException.class,
                () -> new BloomFilterDeduplicator<String>(redisson, null, 10, (String v) -> v));

        assertThrows(NullPointerException.class,
                () -> new BloomFilterDeduplicator<String>(redisson, "bf", 10, null));
    }

    @Test
    void negativeExpectedInsertionsAreRejected() {
        RedissonClient redisson = mock(RedissonClient.class);

        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilterDeduplicator<String>(redisson, "bf", -1, (String v) -> v));
    }

    @Test
    void boundaryFalseProbabilities() {
        RedissonClient redisson = mock(RedissonClient.class);

        // Just below 0
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilterDeduplicator<String>(redisson, "bf", 10, -0.0001, (String v) -> v));

        // Just above 1
        assertThrows(IllegalArgumentException.class,
                () -> new BloomFilterDeduplicator<String>(redisson, "bf", 10, 1.0001, (String v) -> v));
    }

    @Test
    void validBoundaryFalseProbabilities() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter("bf")).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);

        // Very small false positive rate (should be valid)
        assertDoesNotThrow(() -> new BloomFilterDeduplicator<String>(redisson, "bf", 10, 0.000001, (String v) -> v));

        // False positive rate just below 1.0 (should be valid)
        assertDoesNotThrow(() -> new BloomFilterDeduplicator<String>(redisson, "bf", 10, 0.9999, (String v) -> v));
    }
}
