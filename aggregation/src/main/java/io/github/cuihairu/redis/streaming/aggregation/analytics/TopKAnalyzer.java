package io.github.cuihairu.redis.streaming.aggregation.analytics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Top-K analyzer using Redis Sorted Sets to track the most frequent items
 */
@Slf4j
public class TopKAnalyzer {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final int k;
    private final Duration windowSize;

    public TopKAnalyzer(RedissonClient redissonClient, String keyPrefix, int k, Duration windowSize) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.k = k;
        this.windowSize = windowSize;
    }

    /**
     * Record an occurrence of an item
     *
     * @param category the category (e.g., "pages", "users", "products")
     * @param item the item to record
     * @return the current score for this item
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
     * @return the current score for this item
     */
    public double recordItem(String category, String item, double weight) {
        String key = getKeyForCategory(category);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        // Add weight to the item's score
        double newScore = sortedSet.addScore(item, weight);

        // Optionally trim to keep only top items (to manage memory)
        long currentSize = sortedSet.size();
        if (currentSize > k * 2) { // Keep more than k for better accuracy
            // Remove items with lowest scores
            sortedSet.removeRangeByRank(0, (int) (currentSize - k * 2));
        }

        log.debug("Recorded item '{}' in category '{}' with weight {}, new score: {}",
                item, category, weight, newScore);

        return newScore;
    }

    /**
     * Get the top K items for a category
     *
     * @param category the category
     * @return list of top K items with their scores
     */
    public List<TopKItem> getTopK(String category) {
        String key = getKeyForCategory(category);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        // Get top K items in descending order
        Collection<String> topItems = sortedSet.valueRangeReversed(0, k - 1);

        return topItems.stream()
                .map(item -> {
                    Double score = sortedSet.getScore(item);
                    return new TopKItem(item, score != null ? score : 0.0, Instant.now());
                })
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
     * Get the rank of a specific item
     *
     * @param category the category
     * @param item the item
     * @return the rank (1-based), or -1 if not found in top K
     */
    public int getRank(String category, String item) {
        String key = getKeyForCategory(category);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        Integer rank = sortedSet.revRank(item);
        return rank != null ? rank + 1 : -1; // Convert to 1-based ranking
    }

    /**
     * Get the score of a specific item
     *
     * @param category the category
     * @param item the item
     * @return the score, or 0.0 if not found
     */
    public double getScore(String category, String item) {
        String key = getKeyForCategory(category);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        Double score = sortedSet.getScore(item);
        return score != null ? score : 0.0;
    }

    /**
     * Reset the data for a category
     *
     * @param category the category to reset
     */
    public void reset(String category) {
        String key = getKeyForCategory(category);
        redissonClient.getScoredSortedSet(key).clear();
        log.info("Reset Top-K data for category '{}'", category);
    }

    /**
     * Remove a specific item from a category
     *
     * @param category the category
     * @param item the item to remove
     * @return true if the item was removed, false if it didn't exist
     */
    public boolean removeItem(String category, String item) {
        String key = getKeyForCategory(category);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        boolean removed = sortedSet.remove(item);
        if (removed) {
            log.info("Removed item '{}' from category '{}'", item, category);
        }
        return removed;
    }

    private String getKeyForCategory(String category) {
        return keyPrefix + ":topk:" + category;
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