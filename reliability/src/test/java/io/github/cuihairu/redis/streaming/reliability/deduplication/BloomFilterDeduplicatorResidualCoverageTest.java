package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the residual {@link BloomFilterDeduplicator} branches: a rejected
 * {@code add()} must not inflate the unique count, and the expected false
 * probability falls back to 0.0 when the filter reports no expected insertions.
 */
class BloomFilterDeduplicatorResidualCoverageTest {

    @SuppressWarnings("unchecked")
    private RBloomFilter<String> existingFilter(RedissonClient redisson) {
        RBloomFilter<String> bf = mock(RBloomFilter.class);
        when(redisson.<String>getBloomFilter(anyString())).thenReturn(bf);
        when(bf.isExists()).thenReturn(true);
        return bf;
    }

    @Test
    void rejectedAddDoesNotInflateUniqueCount() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBloomFilter<String> bf = existingFilter(redisson);
        when(bf.contains("k")).thenReturn(false);
        when(bf.add("k")).thenReturn(false);

        BloomFilterDeduplicator<String> dedup =
                new BloomFilterDeduplicator<>(redisson, "bloom", 100, s -> s);

        assertFalse(dedup.checkAndMark("k"),
                "a not-yet-seen key is reported as new even when add() rejects the insert");
        assertEquals(0L, dedup.getUniqueCount(), "a rejected add must not increment the unique count");
    }

    @Test
    void expectedProbabilityIsZeroWhenFilterHasNoExpectedInsertions() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBloomFilter<String> bf = existingFilter(redisson);
        when(bf.getExpectedInsertions()).thenReturn(0L);

        BloomFilterDeduplicator<String> dedup =
                new BloomFilterDeduplicator<>(redisson, "bloom", 100, s -> s);

        assertEquals(0.0, dedup.getExpectedFalseProbability());
    }

    @Test
    void expectedProbabilityUsesConfiguredRateWhenInitialized() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBloomFilter<String> bf = existingFilter(redisson);
        when(bf.getExpectedInsertions()).thenReturn(1_000L);
        when(bf.getFalseProbability()).thenReturn(0.02);

        BloomFilterDeduplicator<String> dedup =
                new BloomFilterDeduplicator<>(redisson, "bloom", 100, 0.02, s -> s);

        assertEquals(0.02, dedup.getExpectedFalseProbability(), 1e-9);
    }
}
