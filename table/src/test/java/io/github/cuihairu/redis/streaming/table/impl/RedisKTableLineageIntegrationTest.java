package io.github.cuihairu.redis.streaming.table.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import io.github.cuihairu.redis.streaming.table.KTable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the B-24 derivation lineage against real Redis:
 * per-operation retention bounds repeated derivations, TTL lands on derived
 * hashes, and {@code delete()} reclaims the whole derivation tree.
 */
@Tag("integration")
class RedisKTableLineageIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static int countKeys(RedissonClient client, String pattern) {
        List<String> keys = new ArrayList<>();
        client.getKeys().getKeysByPattern(pattern).forEach(keys::add);
        return keys.size();
    }

    @Test
    void retentionBoundsRepeatedDerivationsAndDeleteReclaimsEverything() {
        RedissonClient client = createClient();
        String name = "tbl-lin-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKTable<String, Integer> users = new RedisKTable<>(client, name, String.class, Integer.class);
            users.put("a", 1);
            users.put("b", 2);

            List<String> generations = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final int offset = i;
                RedisKTable<String, Integer> gen = (RedisKTable<String, Integer>) users.mapValues(v -> v + offset);
                generations.add(gen.getTableName());
                assertEquals(1 + offset, gen.get("a"), "each generation must be readable right after derivation");
            }

            assertTrue(countKeys(client, name + ":mapValues:*") <= RedisKTable.DEFAULT_DERIVED_RETENTION,
                    "a micro-batch style derivation loop must not grow the keyspace past the retention count");

            // a derived chain and a grouped result, both registered in the lineage
            RedisKTable<String, Integer> filtered =
                    (RedisKTable<String, Integer>) users.filter((k, v) -> v > 0);
            KTable<String, Long> counted = users.groupBy(kv -> "g").count();

            assertTrue(countKeys(client, name + ":__derived") >= 1, "lineage hash exists while derivations are live");

            users.delete();

            assertEquals(0, countKeys(client, name + "*"),
                    "delete() must reclaim the base table, its lineage and every derived table");
            for (String gen : generations) {
                assertFalse(client.getMap(gen, org.redisson.client.codec.StringCodec.INSTANCE).size() > 0,
                        "generation " + gen + " must be gone");
            }
            assertFalse(client.getMap(filtered.getTableName()).size() > 0);
            assertFalse(client.getMap(((RedisKTable<String, Long>) counted).getTableName()).size() > 0);
        } finally {
            client.getKeys().deleteByPattern(name + "*");
            client.shutdown();
        }
    }

    @Test
    void derivedTablesExpireByConfiguredTtl() {
        RedissonClient client = createClient();
        String name = "tbl-ttl-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKTable<String, Integer> users = new RedisKTable<>(client, name, String.class, Integer.class);
            users.put("a", 1);
            users.setDerivedTableTtl(Duration.ofMinutes(10));

            RedisKTable<String, Integer> mapped = (RedisKTable<String, Integer>) users.mapValues(v -> v);
            long ttlMillis = client.getMap(mapped.getTableName(),
                    org.redisson.client.codec.StringCodec.INSTANCE).remainTimeToLive();

            assertTrue(ttlMillis > 0, "derived hash must carry a TTL, got " + ttlMillis);
            assertTrue(ttlMillis <= Duration.ofMinutes(10).toMillis(),
                    "TTL must not exceed the configured duration, got " + ttlMillis);
        } finally {
            client.getKeys().deleteByPattern(name + "*");
            client.shutdown();
        }
    }
}
