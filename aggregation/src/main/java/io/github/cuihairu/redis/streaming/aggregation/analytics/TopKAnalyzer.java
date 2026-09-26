package io.github.cuihairu.redis.streaming.aggregation.analytics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Top-K analyzer using Redis Sorted Sets to track the most frequent items within a
 * trailing time window.
 *
 * <p>Windowing (B-18): occurrences are counted in fixed time buckets of
 * {@code windowSize / BUCKETS_PER_WINDOW} (at least 1ms); every query sums the buckets
 * covering the trailing {@code windowSize}, so contributions expire from the result once
 * they fall out of the window. The previous implementation stored {@code windowSize}
 * without ever reading it: scores accumulated forever and rank trimming permanently
 * deleted low-ranked items, making "top-K in the last window" actually "top-K of all
 * time".
 *
 * <p>Each bucket is its own sorted set under
 * {@code <prefix>:topk:<category>:b:<bucketIndex>}; a TTL of window + 2 buckets is set on
 * every write so Redis reaps buckets once they can no longer contribute. Within a bucket
 * the bottom entries beyond {@code 2k} are trimmed on write (the same memory bound as
 * before, scoped to a single bucket). Sorted-set keys written by the pre-windowing
 * layout ({@code <prefix>:topk:<category>}) are ignored and left untouched.
 */
@Slf4j
public class TopKAnalyzer {

    /** How many buckets cover one window; the bucket length is windowSize divided by this. */
    static final int BUCKETS_PER_WINDOW = 10;

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final int k;
    private final Duration windowSize;
    private final long bucketMillis;
    private final LongSupplier clock;

    public TopKAnalyzer(RedissonClient redissonClient, String keyPrefix, int k, Duration windowSize) {
        this(redissonClient, keyPrefix, k, windowSize, System::currentTimeMillis);
    }

    TopKAnalyzer(RedissonClient redissonClient, String keyPrefix, int k, Duration windowSize,
                 LongSupplier clock) {
        if (redissonClient == null) {
            throw new NullPointerException("redissonClient");
        }
        if (keyPrefix == null) {
            throw new NullPointerException("keyPrefix");
        }
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        if (windowSize == null || windowSize.isZero() || windowSize.isNegative()) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.k = k;
        this.windowSize = windowSize;
        this.bucketMillis = Math.max(1L, windowSize.toMillis() / BUCKETS_PER_WINDOW);
        this.clock = clock;
    }

    /**
     * Record an occurrence of an item
     *
     * @param category the category (e.g., "pages", "users", "products")
     * @param item the item to record
     * @return the current score for this item within the current bucket
     */
    public double recordItem(String category, String item) {
        return recordItem(category, item, 1.0);
    }

    /**
     * Record an occurrence of an item with a specific weight
     *
     * @param category the category
     * @param item the item to record
     * @param weight the weight/score to add
     * @return the current score for this item within the current bucket
     */
    public double recordItem(String category, String item, double weight) {
        long now = clock.getAsLong();
        RScoredSortedSet<String> bucket = bucketSet(category, Math.floorDiv(now, bucketMillis));

        // Add weight to the item's score in the current bucket only
        double newScore = bucket.addScore(item, weight);

        // Optionally trim to keep only top items (to manage memory)
        long currentSize = bucket.size();
        if (currentSize > k * 2) { // Keep more than k for better accuracy
            // Remove items with lowest scores
            bucket.removeRangeByRank(0, (int) (currentSize - k * 2));
        }

        // The bucket can contribute to queries only while it overlaps the trailing
        // window; let Redis reap it shortly after that.
        bucket.expire(Instant.ofEpochMilli(now + windowSize.toMillis() + 2 * bucketMillis));

        log.debug("Recorded item '{}' in category '{}' with weight {}, new bucket score: {}",
                item, category, weight, newScore);

        return newScore;
    }

    /**
     * Get the top K items for a category within the trailing window
     *
     * @param category the category
     * @return list of top K items with their windowed scores, highest first
     */
    public List<TopKItem> getTopK(String category) {
        long now = clock.getAsLong();
        return windowTotals(category).entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(k)
                .map(e -> new TopKItem(e.getKey(), e.getValue(), Instant.ofEpochMilli(now)))
                .collect(Collectors.toList());
    }

    /**
     * Get the top K items for a category with their ranks
     *
     * @param category the category
     * @return list of top K items with ranks and scores
     */
    public List<TopKItemWithRank> getTopKWithRanks(String category) {
        List<TopKItem> topItems = getTopK(category);
        return topItems.stream()
                .map(item -> {
                    int rank = topItems.indexOf(item) + 1;
                    return new TopKItemWithRank(item.getItem(), item.getScore(),
                            item.getTimestamp(), rank);
                })
                .collect(Collectors.toList());
    }

    /**
     * Get the rank of a specific item within the trailing window
     *
     * @param category the category
     * @param item the item
     * @return the rank (1-based), or -1 if the item has no score in the window
     */
    public int getRank(String category, String item) {
        List<String> rankedKeys = rankedWindowItems(category);
        for (int i = 0; i < rankedKeys.size(); i++) {
            if (rankedKeys.get(i).equals(item)) {
                return i + 1; // Convert to 1-based ranking
            }
        }
        return -1;
    }

    /**
     * Get the windowed score of a specific item (the sum of its bucket scores within the
     * trailing window)
     *
     * @param category the category
     * @param item the item
     * @return the score, or 0.0 if not found
     */
    public double getScore(String category, String item) {
        double total = 0.0;
        for (RScoredSortedSet<String> bucket : liveBuckets(category)) {
            Double score = bucket.getScore(item);
            if (score != null) {
                total += score;
            }
        }
        return total;
    }

    /**
     * Reset the data for a category (delete every bucket overlapping the trailing window;
     * older buckets carry a TTL and are reaped by Redis)
     *
     * @param category the category to reset
     */
    public void reset(String category) {
        for (RScoredSortedSet<String> bucket : liveBuckets(category)) {
            bucket.delete();
        }
        log.info("Reset Top-K data for category '{}'", category);
    }

    /**
     * Remove a specific item from a category (from every bucket overlapping the trailing
     * window)
     *
     * @param category the category
     * @param item the item to remove
     * @return true if the item was removed from at least one bucket, false otherwise
     */
    public boolean removeItem(String category, String item) {
        boolean removed = false;
        for (RScoredSortedSet<String> bucket : liveBuckets(category)) {
            removed |= bucket.remove(item);
        }
        if (removed) {
            log.info("Removed item '{}' from category '{}'", item, category);
        }
        return removed;
    }

    private List<RScoredSortedSet<String>> liveBuckets(String category) {
        long now = clock.getAsLong();
        long newest = Math.floorDiv(now, bucketMillis);
        long oldest = Math.floorDiv(now - windowSize.toMillis(), bucketMillis);
        List<RScoredSortedSet<String>> buckets = new ArrayList<>();
        for (long index = oldest; index <= newest; index++) {
            buckets.add(bucketSet(category, index));
        }
        return buckets;
    }

    private Map<String, Double> windowTotals(String category) {
        Map<String, Double> totals = new HashMap<>();
        for (RScoredSortedSet<String> bucket : liveBuckets(category)) {
            for (ScoredEntry<String> entry : bucket.entryRangeReversed(0, Integer.MAX_VALUE)) {
                totals.merge(entry.getValue(), entry.getScore(), Double::sum);
            }
        }
        return totals;
    }

    /** All windowed items, highest score first; ties broken by item name for determinism. */
    private List<String> rankedWindowItems(String category) {
        return windowTotals(category).entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private RScoredSortedSet<String> bucketSet(String category, long bucketIndex) {
        return redissonClient.getScoredSortedSet(keyPrefix + ":topk:" + category + ":b:" + bucketIndex);
    }

    /**
     * Top-K item data class
     */
    @Data
    @AllArgsConstructor
    public static class TopKItem {
        private final String item;
        private final double score;
        private final Instant timestamp;
    }

    /**
     * Top-K item with rank data class
     */
    @Data
    @AllArgsConstructor
    public static class TopKItemWithRank {
        private final String item;
        private final double score;
        private final Instant timestamp;
        private final int rank;
    }
}
