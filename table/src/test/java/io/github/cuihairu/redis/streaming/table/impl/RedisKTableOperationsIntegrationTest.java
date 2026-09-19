package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for {@link RedisKTable}: storage round-trips plus the
 * view/join materialization semantics against real Redis.
 */
@Tag("integration")
class RedisKTableOperationsIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void storageViewsAndJoins() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String name = "tbl-" + uid;
        try {
            RedisKTable<String, Integer> users = new RedisKTable<>(client, name, String.class, Integer.class);
            users.put("a", 1);
            users.put("b", 2);
            users.put("c", 3);

            assertEquals(3, users.size());
            assertEquals(1, users.get("a"));
            assertNull(users.get("missing"));
            assertEquals(Map.of("a", 1, "b", 2, "c", 3), users.getState());
            assertEquals(name, users.getTableName());
            assertNotNull(users.toString());

            // mapValues (unary + keyed): views are materialized into new Redis tables
            RedisKTable<String, String> names = (RedisKTable<String, String>) users.mapValues(v -> "u-" + v);
            assertEquals("u-1", names.get("a"));
            RedisKTable<String, String> keyed = (RedisKTable<String, String>) users.mapValues((k, v) -> k + "=" + v);
            assertEquals("b=2", keyed.get("b"));

            // filter view keeps surviving entries
            RedisKTable<String, Integer> big = (RedisKTable<String, Integer>) users.filter((k, v) -> v > 1);
            assertEquals(2, big.get("b"));
            assertNull(big.get("a"));

            // join with another Redis table (inner join drops unmatched)
            RedisKTable<String, String> profiles = new RedisKTable<>(client, name + "-prof", String.class, String.class);
            profiles.put("a", "alpha");
            RedisKTable<String, String> joined = (RedisKTable<String, String>) users.join(profiles, (v, p) -> p + ":" + v);
            assertEquals("alpha:1", joined.get("a"));
            assertNull(joined.get("b"));

            // leftJoin with an in-memory table (missing right side -> null input to joiner)
            InMemoryKTable<String, String> right = new InMemoryKTable<>();
            right.put("b", "bee");
            RedisKTable<String, String> left = (RedisKTable<String, String>) users.leftJoin(right,
                    (v, p) -> p == null ? "none" : p + "/" + v);
            assertEquals("bee/2", left.get("b"));
            assertEquals("none", left.get("a"));

            // toStream materializes the snapshot into the in-memory engine
            List<KTable.KeyValue<String, Integer>> out = new CopyOnWriteArrayList<>();
            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            users.toStream().addSink(out::add);
            assertEquals(3, out.size());

            users.delete();
            assertEquals(0, client.getMap(name).size());
        } finally {
            client.getKeys().deleteByPattern(name + "*");
            client.shutdown();
        }
    }
}
