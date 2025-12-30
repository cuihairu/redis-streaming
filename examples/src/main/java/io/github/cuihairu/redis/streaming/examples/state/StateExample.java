package io.github.cuihairu.redis.streaming.examples.state;

import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashMap;

/**
 * Example demonstrating State module usage.
 *
 * Requires Redis at redis://127.0.0.1:6379 (override via REDIS_URL).
 */
@Slf4j
public class StateExample {

    public static void main(String[] args) throws Exception {
        log.info("Starting State Example");

        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        try {
            RedisStateBackend stateBackend = new RedisStateBackend(redisson, "example:state:");

            demonstrateValueState(stateBackend);
            demonstrateMapState(stateBackend);
            demonstrateListState(stateBackend);
            demonstrateSetState(stateBackend);
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

        preferences.put("theme", "dark");
        preferences.put("language", "en");
        preferences.put("timezone", "UTC");

        log.info("Theme: {}", preferences.get("theme"));
        log.info("Language: {}", preferences.get("language"));

        log.info("All preferences:");
        preferences.entries().forEach(entry ->
                log.info("  {} = {}", entry.getKey(), entry.getValue()));

        preferences.clear();
    }

    private static void demonstrateListState(RedisStateBackend stateBackend) {
        log.info("=== ListState Example: Activity Log ===");

        StateDescriptor<String> descriptor = new StateDescriptor<>("activity-log", String.class);
        ListState<String> activityLog = stateBackend.createListState(descriptor);

        activityLog.add("login");
        activityLog.add("view_product");
        activityLog.add("add_to_cart");
        activityLog.add("checkout");

        log.info("User activity:");
        int index = 1;
        for (String activity : activityLog.get()) {
            log.info("  {}. {}", index++, activity);
        }

        activityLog.clear();
    }

    private static void demonstrateSetState(RedisStateBackend stateBackend) {
        log.info("=== SetState Example: Unique Visitors ===");

        StateDescriptor<String> descriptor = new StateDescriptor<>("unique-visitors", String.class);
        SetState<String> visitors = stateBackend.createSetState(descriptor);

        String[] userIds = {"user1", "user2", "user1", "user3", "user2", "user4"};

        for (String userId : userIds) {
            boolean isNew = visitors.add(userId);
            log.info("User {} visited (new: {})", userId, isNew);
        }

        log.info("Total unique visitors: {}", visitors.size());

        log.info("Unique visitors:");
        visitors.get().forEach(userId -> log.info("  - {}", userId));

        visitors.clear();
    }

    private static void demonstrateStatefulWordCount(RedisStateBackend stateBackend) {
        log.info("=== Stateful Processing Example: Word Count ===");

        MapState<String, Long> wordCounts = stateBackend.createMapState(
                "word-counts", String.class, Long.class);

        String[] texts = {"hello world", "hello streaming", "world of streaming"};
        for (String text : texts) {
            for (String word : text.split(" ")) {
                Long count = wordCounts.get(word);
                if (count == null) count = 0L;
                wordCounts.put(word, count + 1);
            }
        }

        log.info("Word counts:");
        wordCounts.entries().forEach(entry ->
                log.info("  '{}': {}", entry.getKey(), entry.getValue()));

        wordCounts.clear();
    }
}

