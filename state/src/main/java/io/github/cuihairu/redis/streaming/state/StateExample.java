package io.github.cuihairu.redis.streaming.state;

import io.github.cuihairu.redis.streaming.api.state.*;
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Arrays;

/**
 * Example demonstrating State module usage
 */
@Slf4j
public class StateExample {

    public static void main(String[] args) throws Exception {
        log.info("Starting State Example");

        // Setup Redis connection
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient redisson = Redisson.create(config);

        try {
            // Create state backend
            RedisStateBackend stateBackend = new RedisStateBackend(redisson, "example:state:");

            // Example 1: ValueState - User session counter
            demonstrateValueState(stateBackend);

            // Example 2: MapState - User preferences
            demonstrateMapState(stateBackend);

            // Example 3: ListState - User activity log
            demonstrateListState(stateBackend);

            // Example 4: SetState - Unique visitors
            demonstrateSetState(stateBackend);

            // Example 5: Stateful word count
            demonstrateStatefulWordCount(stateBackend);

            log.info("State Example completed successfully");

        } finally {
            redisson.shutdown();
        }
    }

    private static void demonstrateValueState(RedisStateBackend stateBackend) {
        log.info("=== ValueState Example: Session Counter ===");

        StateDescriptor<Integer> descriptor = new StateDescriptor<>("session-counter", Integer.class);
        ValueState<Integer> counter = stateBackend.createValueState(descriptor);

        // Simulate user visits
        for (int i = 0; i < 5; i++) {
            Integer count = counter.value();
            if (count == null) count = 0;
            count++;
            counter.update(count);
            log.info("User visit #{}", count);
        }

        log.info("Total visits: {}", counter.value());
        counter.clear();
    }

    private static void demonstrateMapState(RedisStateBackend stateBackend) {
        log.info("=== MapState Example: User Preferences ===");

        MapState<String, String> preferences = stateBackend.createMapState(
                "user-preferences", String.class, String.class);

        // Set preferences
        preferences.put("theme", "dark");
        preferences.put("language", "en");
        preferences.put("timezone", "UTC");

        log.info("Theme: {}", preferences.get("theme"));
        log.info("Language: {}", preferences.get("language"));

        // List all preferences
        log.info("All preferences:");
        preferences.entries().forEach(entry ->
                log.info("  {} = {}", entry.getKey(), entry.getValue()));

        preferences.clear();
    }

    private static void demonstrateListState(RedisStateBackend stateBackend) {
        log.info("=== ListState Example: Activity Log ===");

        StateDescriptor<String> descriptor = new StateDescriptor<>("activity-log", String.class);
        ListState<String> log = stateBackend.createListState(descriptor);

        // Add activities
        log.add("login");
        log.add("view_product");
        log.add("add_to_cart");
        log.add("checkout");

        // Print activity log
        StateExample.log.info("User activity:");
        int index = 1;
        for (String activity : log.get()) {
            StateExample.log.info("  {}. {}", index++, activity);
        }

        log.clear();
    }

    private static void demonstrateSetState(RedisStateBackend stateBackend) {
        log.info("=== SetState Example: Unique Visitors ===");

        StateDescriptor<String> descriptor = new StateDescriptor<>("unique-visitors", String.class);
        SetState<String> visitors = stateBackend.createSetState(descriptor);

        // Track visitors (with duplicates)
        String[] userIds = {"user1", "user2", "user1", "user3", "user2", "user4"};

        for (String userId : userIds) {
            boolean isNew = visitors.add(userId);
            log.info("User {} visited (new: {})", userId, isNew);
        }

        log.info("Total unique visitors: {}", visitors.size());

        // List all unique visitors
        log.info("Unique visitors:");
        visitors.get().forEach(userId -> log.info("  - {}", userId));

        visitors.clear();
    }

    private static void demonstrateStatefulWordCount(RedisStateBackend stateBackend) {
        log.info("=== Stateful Processing Example: Word Count ===");

        // Use MapState to count words
        MapState<String, Long> wordCounts = stateBackend.createMapState(
                "word-counts", String.class, Long.class);

        // Process some text
        String[] texts = {
                "hello world",
                "hello streaming",
                "world of streaming"
        };

        for (String text : texts) {
            String[] words = text.split(" ");
            for (String word : words) {
                Long count = wordCounts.get(word);
                if (count == null) count = 0L;
                count++;
                wordCounts.put(word, count);
            }
        }

        // Print word counts
        log.info("Word counts:");
        wordCounts.entries().forEach(entry ->
                log.info("  '{}': {}", entry.getKey(), entry.getValue()));

        wordCounts.clear();
    }
}
