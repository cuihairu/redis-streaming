package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers {@code DeduplicatorFactory} creation overloads and strategy dispatch. */
class DeduplicatorFactoryCoverageTest {

    private RedissonClient redisson;
    private final Function<String, String> keyExtractor = s -> s;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBloomFilter<Object> bloom = mock(RBloomFilter.class);
        @SuppressWarnings("unchecked")
        RSet<String> set = mock(RSet.class);
        when(redisson.<Object>getBloomFilter(anyString())).thenReturn(bloom);
        when(redisson.<String>getSet(anyString())).thenReturn(set);
    }

    @Test
    void createBloomFilterOverloads() {
        assertNotNull(DeduplicatorFactory.createBloomFilter(redisson, "b1", 100, keyExtractor));
        assertNotNull(DeduplicatorFactory.createBloomFilter(redisson, "b2", 100, 0.01, keyExtractor));
    }

    @Test
    void createDispatchesPerStrategy() {
        assertNotNull(DeduplicatorFactory.create(DeduplicatorFactory.DeduplicationStrategy.BLOOM_FILTER, redisson, "d1", keyExtractor));
        assertNotNull(DeduplicatorFactory.create(DeduplicatorFactory.DeduplicationStrategy.SET, redisson, "d2", keyExtractor));
        assertNotNull(DeduplicatorFactory.create(DeduplicatorFactory.DeduplicationStrategy.WINDOWED, redisson, "d3", keyExtractor));
    }

    @Test
    void windowedAndSetFactories() {
        Deduplicator<String> windowed = DeduplicatorFactory.createWindowed(redisson, "w1", Duration.ofMinutes(5), keyExtractor);
        assertNotNull(windowed);
        Deduplicator<String> set = DeduplicatorFactory.createSet(redisson, "s1", keyExtractor);
        assertNotNull(set);
    }
}
