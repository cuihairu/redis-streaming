package io.github.cuihairu.redis.streaming.reliability.deduplication;

import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Time-window based deduplicator using Redis.
 * Elements are considered duplicates only within a specific time window.
 * Each element expires individually after the window duration has passed since it was
 * last seen (B-09).
 *
 * <p>Elements are tracked in a Redis sorted set with the timestamp of the last
 * occurrence as the score. Writes prune entries older than the window, so the set only
 * ever holds in-window elements and stays bounded by (traffic rate x window), and a TTL
 * of window + a safety margin reaps the whole key once traffic stops.
 *
 * <p>Sets written by the pre-B-09 implementation (a plain set without per-element
 * timestamps) are migrated on first access: their members are kept with the migration
 * instant as the last-seen time, i.e. they count as seen exactly once more.
 *
 * @param <T> the type of elements to deduplicate
 */
public class WindowedDeduplicator<T> implements Deduplicator<T> {

    private static final Logger log = LoggerFactory.getLogger(WindowedDeduplicator.class);

    /**
     * Safety margin added to the window when stamping the key's TTL: the key must
     * outlive its youngest entry even with modest clock skew between instances.
     */
    static final Duration TTL_MARGIN = Duration.ofSeconds(60);

    private final RedissonClient redissonClient;
    private final String name;
    private final Duration windowDuration;
    private final Function<T, String> keyExtractor;
    private final LongSupplier clock;

    /**
     * Lazily resolved sorted set; resolved through {@link #migratedSet()} so a key left by
     * the pre-B-09 plain-set layout is migrated before the first scored access.
     */
    private volatile RScoredSortedSet<String> scoredSet;

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
        this(redissonClient, name, windowDuration, keyExtractor, System::currentTimeMillis);
    }

    WindowedDeduplicator(
            RedissonClient redissonClient,
            String name,
            Duration windowDuration,
            Function<T, String> keyExtractor,
            LongSupplier clock) {
        Objects.requireNonNull(redissonClient, "RedissonClient cannot be null");
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(windowDuration, "Window duration cannot be null");
        Objects.requireNonNull(keyExtractor, "KeyExtractor cannot be null");
        Objects.requireNonNull(clock, "Clock cannot be null");

        if (windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("Window duration must be positive");
        }

        this.redissonClient = redissonClient;
        this.name = name;
        this.windowDuration = windowDuration;
        this.keyExtractor = keyExtractor;
        this.clock = clock;
    }

    @Override
    public boolean isDuplicate(T element) {
        if (element == null) {
            return false;
        }
        String key = keyExtractor.apply(element);
        RScoredSortedSet<String> set = migratedSet();
        Double score = set.getScore(key);
        return isInWindow(score);
    }

    @Override
    public void markAsSeen(T element) {
        if (element == null) {
            return;
        }
        String key = keyExtractor.apply(element);
        long now = clock.getAsLong();
        RScoredSortedSet<String> set = migratedSet();
        pruneExpired(set, now);
        set.add(now, key);
        stampTtl(set);
    }

    @Override
    public boolean checkAndMark(T element) {
        if (element == null) {
            return false;
        }
        String key = keyExtractor.apply(element);
        long now = clock.getAsLong();
        RScoredSortedSet<String> set = migratedSet();
        pruneExpired(set, now);
        Double score = set.getScore(key);
        boolean duplicate = isInWindow(score);
        set.add(now, key);
        stampTtl(set);
        return duplicate;
    }

    @Override
    public void clear() {
        migratedSet().delete();
    }

    @Override
    public long getUniqueCount() {
        long now = clock.getAsLong();
        RScoredSortedSet<String> set = migratedSet();
        pruneExpired(set, now);
        return set.size();
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
        return migratedSet().remainTimeToLive();
    }

    private boolean isInWindow(Double score) {
        // strictly inside the window: an entry whose window has elapsed to the exact
        // millisecond is no longer a duplicate
        return score != null && clock.getAsLong() - score < windowDuration.toMillis();
    }

    /** Drop entries whose element-level window has elapsed so the set stays bounded. */
    private void pruneExpired(RScoredSortedSet<String> set, long now) {
        // scores are epoch millis, so 0 is a safe lower bound ("-inf" is not portable
        // through Redisson's score encoding)
        set.removeRangeByScore(0, true, now - windowDuration.toMillis(), false);
    }

    private void stampTtl(RScoredSortedSet<String> set) {
        // The set only holds in-window entries (writes prune), so this TTL is a backstop
        // that reaps the key once traffic stops; it must not be the expiry mechanism.
        set.expire(windowDuration.plus(TTL_MARGIN));
    }

    /**
     * Resolve the scored set, migrating a plain set left by the pre-B-09 layout on first
     * access: its members cannot know their original timestamps, so they are kept with
     * the migration instant as their last-seen time.
     */
    private RScoredSortedSet<String> migratedSet() {
        RScoredSortedSet<String> set = scoredSet;
        if (set == null) {
            synchronized (this) {
                set = scoredSet;
                if (set == null) {
                    set = redissonClient.<String>getScoredSortedSet(name);
                    migrateLegacyPlainSet(set);
                    scoredSet = set;
                }
            }
        }
        return set;
    }

    private void migrateLegacyPlainSet(RScoredSortedSet<String> target) {
        try {
            RType type = redissonClient.getKeys().getType(name);
            if (type != RType.SET) {
                return;
            }
            RSet<String> legacy = redissonClient.getSet(name);
            Set<String> members = legacy.readAll();
            long now = clock.getAsLong();
            legacy.delete();
            if (!members.isEmpty()) {
                java.util.Map<String, Double> stamped = new java.util.HashMap<>(members.size());
                for (String member : members) {
                    stamped.put(member, (double) now);
                }
                target.addAll(stamped);
            }
            log.info("Migrated legacy deduplication set '{}' ({} members) to per-element expiry",
                    name, members.size());
        } catch (Exception e) {
            log.warn("Failed to migrate legacy deduplication set '{}'", name, e);
        }
    }
}
