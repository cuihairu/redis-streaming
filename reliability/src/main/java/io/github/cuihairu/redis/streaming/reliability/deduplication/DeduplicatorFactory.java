package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.function.Function;

/**
 * Factory for creating different types of deduplicators.
 */
public class DeduplicatorFactory {

    /**
     * Create a Bloom Filter-based deduplicator with default settings.
     *
     * @param redissonClient     the Redisson client
     * @param name               the name of the Bloom Filter
     * @param expectedInsertions expected number of elements
     * @param keyExtractor       function to extract key from element
     * @param <T>                element type
     * @return a Bloom Filter deduplicator
     */
    public static <T> BloomFilterDeduplicator<T> createBloomFilter(
            RedissonClient redissonClient,
            String name,
            long expectedInsertions,
            Function<T, String> keyExtractor) {
        return new BloomFilterDeduplicator<>(
                redissonClient,
                name,
                expectedInsertions,
                keyExtractor
        );
    }

    /**
     * Create a Bloom Filter-based deduplicator with custom false positive rate.
     *
     * @param redissonClient     the Redisson client
     * @param name               the name of the Bloom Filter
     * @param expectedInsertions expected number of elements
     * @param falseProbability   acceptable false positive rate (0.0 to 1.0)
     * @param keyExtractor       function to extract key from element
     * @param <T>                element type
     * @return a Bloom Filter deduplicator
     */
    public static <T> BloomFilterDeduplicator<T> createBloomFilter(
            RedissonClient redissonClient,
            String name,
            long expectedInsertions,
            double falseProbability,
            Function<T, String> keyExtractor) {
        return new BloomFilterDeduplicator<>(
                redissonClient,
                name,
                expectedInsertions,
                falseProbability,
                keyExtractor
        );
    }

    /**
     * Create a Set-based exact deduplicator.
     *
     * @param redissonClient the Redisson client
     * @param name           the name of the set
     * @param keyExtractor   function to extract key from element
     * @param <T>            element type
     * @return a Set deduplicator
     */
    public static <T> SetDeduplicator<T> createSet(
            RedissonClient redissonClient,
            String name,
            Function<T, String> keyExtractor) {
        return new SetDeduplicator<>(redissonClient, name, keyExtractor);
    }

    /**
     * Create a time-windowed deduplicator.
     *
     * @param redissonClient the Redisson client
     * @param name           the name of the deduplication set
     * @param windowDuration the time window for deduplication
     * @param keyExtractor   function to extract key from element
     * @param <T>            element type
     * @return a windowed deduplicator
     */
    public static <T> WindowedDeduplicator<T> createWindowed(
            RedissonClient redissonClient,
            String name,
            Duration windowDuration,
            Function<T, String> keyExtractor) {
        return new WindowedDeduplicator<>(
                redissonClient,
                name,
                windowDuration,
                keyExtractor
        );
    }

    /**
     * Create a deduplicator based on strategy.
     *
     * @param strategy       the deduplication strategy
     * @param redissonClient the Redisson client
     * @param name           the name of the deduplicator
     * @param keyExtractor   function to extract key from element
     * @param <T>            element type
     * @return appropriate deduplicator
     */
    public static <T> Deduplicator<T> create(
            DeduplicationStrategy strategy,
            RedissonClient redissonClient,
            String name,
            Function<T, String> keyExtractor) {
        switch (strategy) {
            case BLOOM_FILTER:
                return createBloomFilter(redissonClient, name, 1_000_000, keyExtractor);
            case SET:
                return createSet(redissonClient, name, keyExtractor);
            case WINDOWED:
                return createWindowed(redissonClient, name, Duration.ofHours(1), keyExtractor);
            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    /**
     * Deduplication strategy enum.
     */
    public enum DeduplicationStrategy {
        /** Bloom Filter - space efficient, probabilistic */
        BLOOM_FILTER,
        /** Set - exact, memory intensive */
        SET,
        /** Windowed - time-based, auto-expiring */
        WINDOWED
    }
}
