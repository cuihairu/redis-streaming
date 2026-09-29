package io.github.cuihairu.redis.streaming.table.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the remaining §15 bullets against real Redis: grouped-table
 * aggregation ({@code groupBy} → {@code count}/{@code reduce}/{@code aggregate}, materialized
 * into Redis-backed result tables) and update propagation (overwriting a key is visible to
 * queries and to every subsequently materialized grouped view, and siblings constructed over
 * the same table name see each other's writes through the shared Redis hash).
 *
 * <p>Join/query basics live in {@link RedisKTableOperationsIntegrationTest}; here the
 * aggregation results themselves are the subject — including the null-group entries being
 * dropped from the materialization.</p>
 */
@Tag("integration")
class RedisKTableGroupingUpdateIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void groupedAggregationsMaterializeIntoRedisBackedTables() {
        RedissonClient client = createClient();
        String name = "agg-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKTable<String, Integer> users = new RedisKTable<>(client, name, String.class, Integer.class);
            users.put("eu-alice", 5);
            users.put("eu-bob", 7);
            users.put("us-carol", 3);
            users.put("xx-dropme", 100); // group selector yields null -> must be dropped

            // group by region prefix; null group keys are excluded from every materialization
            var grouped = users.groupBy(kv -> kv.getKey().startsWith("eu-") ? "EU"
                    : kv.getKey().startsWith("us-") ? "US" : null);

            // grouped views materialize as RedisKTable instances (the KTable interface itself
            // only exposes transformations; queries go through the concrete type, as elsewhere)
            RedisKTable<String, Long> counted = (RedisKTable<String, Long>) grouped.count();
            assertEquals(Map.of("EU", 2L, "US", 1L), counted.getState());
            assertEquals(2L, counted.get("EU"));
            assertNull(counted.get("xx-dropme"));
            assertTrue(counted.getTableName().startsWith(name + ":groupBy:count:"),
                    "count must materialize under the source table's namespace, got " + counted.getTableName());

            RedisKTable<String, Integer> summed = (RedisKTable<String, Integer>) grouped.aggregate(
                    () -> 0,
                    (group, value, acc) -> acc + value,
                    (group, oldAgg, newAgg) -> oldAgg);
            assertEquals(Map.of("EU", 12, "US", 3), summed.getState());

            RedisKTable<String, Integer> reduced = (RedisKTable<String, Integer>) grouped.reduce(Integer::sum, (a, b) -> a);
            assertEquals(Map.of("EU", 12, "US", 3), reduced.getState());
            assertTrue(reduced.getTableName().startsWith(name + ":groupBy:reduce:"),
                    "reduce must materialize under the source table's namespace, got " + reduced.getTableName());

            // results are Redis-backed tables in their own right: mutate one and re-read it
            summed.put("EU", 99);
            assertEquals(99, summed.get("EU"));
        } finally {
            client.getKeys().deleteByPattern(name + "*");
            client.shutdown();
        }
    }

    @Test
    void updatesPropagateToQueriesGroupedViewsAndSiblingInstances() {
        RedissonClient client = createClient();
        String name = "upd-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKTable<String, Integer> t = new RedisKTable<>(client, name, String.class, Integer.class);
            t.put("a", 1);
            t.put("b", 2);

            var grouped = t.groupBy(kv -> "g");

            assertEquals(Map.of("g", 2L), ((RedisKTable<String, Long>) grouped.count()).getState());

            // overwrite an existing key: queries and size reflect it immediately
            t.put("a", 10);
            assertEquals(10, t.get("a"));
            assertEquals(Map.of("a", 10, "b", 2), t.getState());
            assertEquals(2, t.size(), "overwriting a key must not grow the table");

            // re-materialized grouped views read the fresh state: same group count but the
            // updated value flows into aggregates (no stale snapshot, no ghost entries)
            assertEquals(Map.of("g", 2L), ((RedisKTable<String, Long>) grouped.count()).getState());
            RedisKTable<String, Integer> summed = (RedisKTable<String, Integer>) grouped.aggregate(
                    () -> 0, (group, value, acc) -> acc + value, (group, o, n) -> o);
            assertEquals(Map.of("g", 12), summed.getState(), "aggregate must see the updated value 10+2");

            // persistence: a sibling instance over the same table name shares the Redis hash
            RedisKTable<String, Integer> sibling = new RedisKTable<>(client, name, String.class, Integer.class);
            assertEquals(Map.of("a", 10, "b", 2), sibling.getState());
            sibling.put("b", 20);
            assertEquals(20, t.get("b"), "writes through a sibling must be visible to the original");
        } finally {
            client.getKeys().deleteByPattern(name + "*");
            client.shutdown();
        }
    }
}
