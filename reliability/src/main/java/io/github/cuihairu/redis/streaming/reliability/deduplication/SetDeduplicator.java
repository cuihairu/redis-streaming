package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.function.Function;

/**
 * Set-based deduplicator using Redis.
 * Provides exact deduplication with no false positives or negatives.
 *
 * <p>Trade-offs:
 * <ul>
 *   <li>Exact: No false positives or false negatives</li>
 *   <li>Memory intensive: Stores all unique keys</li>
 *   <li>Suitable for smaller datasets or critical deduplication</li>
 * </ul>
 *
 * @param <T> the type of elements to deduplicate
 */
public class SetDeduplicator<T> implements Deduplicator<T> {

    private final RSet<String> deduplicationSet;
    private final Function<T, String> keyExtractor;

    /**
     * Create a Set-based deduplicator.
     *
     * @param redissonClient the Redisson client
     * @param name           the name of the set in Redis
     * @param keyExtractor   function to extract unique key from element
     */
    public SetDeduplicator(
            RedissonClient redissonClient,
            String name,
            Function<T, String> keyExtractor) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(keyExtractor, "KeyExtractor cannot be null");

        this.deduplicationSet = redissonClient.getSet(name);
        this.keyExtractor = keyExtractor;
    }

    @Override
    public boolean isDuplicate(T element) {
        if (element == null) {
            return false;
        }
        String key = keyExtractor.apply(element);
        return deduplicationSet.contains(key);
    }

    @Override
    public void markAsSeen(T element) {
        if (element == null) {
            return;
        }
        String key = keyExtractor.apply(element);
        deduplicationSet.add(key);
    }

    @Override
    public boolean checkAndMark(T element) {
        if (element == null) {
            return false;
        }
        String key = keyExtractor.apply(element);
        // add() returns false if element already exists
        return !deduplicationSet.add(key);
    }

    @Override
    public void clear() {
        deduplicationSet.clear();
    }

    @Override
    public long getUniqueCount() {
        return deduplicationSet.size();
    }

    /**
     * Check if a specific key exists in the set.
     *
     * @param key the key to check
     * @return true if the key exists
     */
    public boolean containsKey(String key) {
        return deduplicationSet.contains(key);
    }

    /**
     * Remove a specific key from the set.
     *
     * @param key the key to remove
     * @return true if the key was removed
     */
    public boolean remove(String key) {
        return deduplicationSet.remove(key);
    }
}
