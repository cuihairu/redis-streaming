package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Time-window based deduplicator using Redis.
 * Elements are considered duplicates only within a specific time window.
 * Old entries automatically expire after the window duration.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Sliding window deduplication</li>
 *   <li>Recent event deduplication</li>
 *   <li>Memory-bounded deduplication</li>
 * </ul>
 *
 * @param <T> the type of elements to deduplicate
 */
public class WindowedDeduplicator<T> implements Deduplicator<T> {

    private final SetDeduplicator<T> setDeduplicator;
    private final Duration windowDuration;
    private final RedissonClient redissonClient;
    private final String name;

    /**
     * Create a windowed deduplicator.
     *
     * @param redissonClient the Redisson client
     * @param name           the name of the deduplication set
     * @param windowDuration the time window for deduplication
     * @param keyExtractor   function to extract unique key from element
     */
    public WindowedDeduplicator(
            RedissonClient redissonClient,
            String name,
            Duration windowDuration,
            Function<T, String> keyExtractor) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(windowDuration, "Window duration cannot be null");
        Objects.requireNonNull(keyExtractor, "KeyExtractor cannot be null");

        if (windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("Window duration must be positive");
        }

        this.redissonClient = redissonClient;
        this.name = name;
        this.windowDuration = windowDuration;
        this.setDeduplicator = new SetDeduplicator<>(redissonClient, name, keyExtractor);

        // Set TTL on the entire set
        redissonClient.getSet(name).expire(windowDuration);
    }

    @Override
    public boolean isDuplicate(T element) {
        return setDeduplicator.isDuplicate(element);
    }

    @Override
    public void markAsSeen(T element) {
        setDeduplicator.markAsSeen(element);
        // Refresh TTL on every write
        redissonClient.getSet(name).expire(windowDuration);
    }

    @Override
    public boolean checkAndMark(T element) {
        boolean isDuplicate = setDeduplicator.checkAndMark(element);
        // Refresh TTL
        redissonClient.getSet(name).expire(windowDuration);
        return isDuplicate;
    }

    @Override
    public void clear() {
        setDeduplicator.clear();
    }

    @Override
    public long getUniqueCount() {
        return setDeduplicator.getUniqueCount();
    }

    /**
     * Get the window duration.
     *
     * @return the window duration
     */
    public Duration getWindowDuration() {
        return windowDuration;
    }

    /**
     * Get remaining time to live for the deduplication window.
     *
     * @return remaining TTL in milliseconds, -1 if no expiration, -2 if key doesn't exist
     */
    public long getRemainingTTL() {
        return redissonClient.getSet(name).remainTimeToLive();
    }
}
