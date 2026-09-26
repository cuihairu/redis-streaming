package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Bloom Filter-based deduplicator using Redis.
 * Provides space-efficient probabilistic deduplication with configurable false positive rate.
 *
 * <p>Trade-offs:
 * <ul>
 *   <li>Space efficient: Uses much less memory than Set-based deduplication</li>
 *   <li>Probabilistic: May have false positives (claiming duplicate when it's not)</li>
 *   <li>No false negatives: Will never miss an actual duplicate</li>
 * </ul>
 *
 * <p>Thread safety: {@link #checkAndMark(Object)} is atomic among threads sharing this
 * instance (B-19). Redisson's {@link RBloomFilter} exposes no server-side
 * check-and-add, so callers running in <b>different processes</b> against the same
 * filter can still both see a fresh element — use {@link SetDeduplicator} (single
 * atomic SADD) when cross-process exactly-once matters.
 *
 * @param <T> the type of elements to deduplicate
 */
public class BloomFilterDeduplicator<T> implements Deduplicator<T> {

    private final RBloomFilter<String> bloomFilter;
    private final Function<T, String> keyExtractor;
    private final long expectedInsertions;
    private final double falseProbability;
    /** guards the contains→add pair of checkAndMark against interleaving (B-19) */
    private final Object stateLock = new Object();
    private final AtomicLong seenCount = new AtomicLong();

    /**
     * Create a Bloom Filter deduplicator with default settings.
     *
     * @param redissonClient the Redisson client
     * @param name           the name of the Bloom Filter in Redis
     * @param expectedInsertions expected number of elements
     * @param keyExtractor   function to extract unique key from element
     */
    public BloomFilterDeduplicator(
            RedissonClient redissonClient,
            String name,
            long expectedInsertions,
            Function<T, String> keyExtractor) {
        this(redissonClient, name, expectedInsertions, 0.03, keyExtractor);
    }

    /**
     * Create a Bloom Filter deduplicator with custom false positive rate.
     *
     * @param redissonClient the Redisson client
     * @param name           the name of the Bloom Filter in Redis
     * @param expectedInsertions expected number of elements
     * @param falseProbability   acceptable false positive rate (0.0 to 1.0)
     * @param keyExtractor   function to extract unique key from element
     */
    public BloomFilterDeduplicator(
            RedissonClient redissonClient,
            String name,
            long expectedInsertions,
            double falseProbability,
            Function<T, String> keyExtractor) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(keyExtractor, "KeyExtractor cannot be null");

        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("Expected insertions must be positive");
        }
        if (falseProbability <= 0 || falseProbability >= 1) {
            throw new IllegalArgumentException("False probability must be between 0 and 1");
        }

        this.bloomFilter = redissonClient.getBloomFilter(name);
        this.keyExtractor = keyExtractor;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;

        // Initialize if not already initialized
        if (!bloomFilter.isExists()) {
            bloomFilter.tryInit(expectedInsertions, falseProbability);
        }
    }

    @Override
    public boolean isDuplicate(T element) {
        if (element == null) {
            return false;
        }
        String key = keyExtractor.apply(element);
        return bloomFilter.contains(key);
    }

    @Override
    public void markAsSeen(T element) {
        if (element == null) {
            return;
        }
        String key = keyExtractor.apply(element);
        if (bloomFilter.add(key)) {
            seenCount.incrementAndGet();
        }
    }

    @Override
    public boolean checkAndMark(T element) {
        if (element == null) {
            return false;
        }
        String key = keyExtractor.apply(element);

        // contains→add must not interleave: two threads passing the contains check
        // before either adds would both report the element as fresh (B-19).
        synchronized (stateLock) {
            boolean exists = bloomFilter.contains(key);
            if (!exists) {
                if (bloomFilter.add(key)) {
                    seenCount.incrementAndGet();
                }
            }
            return exists;
        }
    }

    @Override
    public void clear() {
        // Deleting the Redis key also destroys the filter's sizing parameters; without
        // re-initialization every subsequent add/contains threw "Bloom filter is not
        // initialized!" (B-12). tryInit is a no-op when the key already exists, so this is
        // safe under racing clears.
        synchronized (stateLock) {
            bloomFilter.delete();
            bloomFilter.tryInit(expectedInsertions, falseProbability);
            seenCount.set(0);
        }
    }

    @Override
    public long getUniqueCount() {
        return seenCount.get();
    }

    /**
     * Get the expected false positive probability.
     *
     * @return the expected false positive rate
     */
    public double getExpectedFalseProbability() {
        return bloomFilter.getExpectedInsertions() > 0
                ? bloomFilter.getFalseProbability()
                : 0.0;
    }

    /**
     * Get the current count in the Bloom Filter.
     *
     * @return approximate count of elements
     */
    public long count() {
        return bloomFilter.count();
    }

    /**
     * Check if the Bloom Filter contains a specific key.
     *
     * @param key the key to check
     * @return true if the key might exist, false if definitely doesn't exist
     */
    public boolean containsKey(String key) {
        return bloomFilter.contains(key);
    }
}
