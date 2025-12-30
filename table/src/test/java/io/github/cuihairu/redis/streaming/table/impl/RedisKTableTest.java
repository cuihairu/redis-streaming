package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RedisKTable.
 * Requires Redis to be running.
 */
@Tag("integration")
class RedisKTableTest {

    private RedissonClient redissonClient;
    private RedisKTable<String, String> table;
    private static final String TABLE_NAME = "test-ktable";

    @BeforeEach
    void setUp() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(2);

        redissonClient = Redisson.create(config);
        table = new RedisKTable<>(redissonClient, TABLE_NAME, String.class, String.class);
        table.clear();
    }

    @AfterEach
    void tearDown() {
        if (table != null) {
            table.delete();
        }
        if (redissonClient != null && !redissonClient.isShutdown()) {
            redissonClient.shutdown();
        }
    }

    @Test
    void testPutAndGet() {
        table.put("key1", "value1");
        assertEquals("value1", table.get("key1"));

        table.put("key2", "value2");
        assertEquals("value2", table.get("key2"));

        assertNull(table.get("nonexistent"));
    }

    @Test
    void testPutNullRemovesKey() {
        table.put("key1", "value1");
        assertEquals("value1", table.get("key1"));

        table.put("key1", null);
        assertNull(table.get("key1"));
    }

    @Test
    void testGetState() {
        table.put("key1", "value1");
        table.put("key2", "value2");
        table.put("key3", "value3");

        Map<String, String> state = table.getState();
        assertEquals(3, state.size());
        assertEquals("value1", state.get("key1"));
        assertEquals("value2", state.get("key2"));
        assertEquals("value3", state.get("key3"));
    }

    @Test
    void testSize() {
        assertEquals(0, table.size());

        table.put("key1", "value1");
        assertEquals(1, table.size());

        table.put("key2", "value2");
        assertEquals(2, table.size());

        table.put("key1", "updated");
        assertEquals(2, table.size()); // Still 2, just updated

        table.put("key1", null);
        assertEquals(1, table.size()); // Removed
    }

    @Test
    void testClear() {
        table.put("key1", "value1");
        table.put("key2", "value2");
        assertEquals(2, table.size());

        table.clear();
        assertEquals(0, table.size());
        assertNull(table.get("key1"));
    }

    @Test
    void testMapValues() {
        table.put("key1", "hello");
        table.put("key2", "world");

        KTable<String, String> mapped = table.mapValues((String value) -> value.toUpperCase());
        assertTrue(mapped instanceof RedisKTable);

        RedisKTable<String, String> mappedTable = (RedisKTable<String, String>) mapped;
        assertEquals("HELLO", mappedTable.get("key1"));
        assertEquals("WORLD", mappedTable.get("key2"));

        // Cleanup
        mappedTable.delete();
    }

    @Test
    void testMapValuesWithKey() {
        table.put("key1", "value1");
        table.put("key2", "value2");

        KTable<String, String> mapped = table.mapValues((key, value) -> key + ":" + value);
        assertTrue(mapped instanceof RedisKTable);

        RedisKTable<String, String> mappedTable = (RedisKTable<String, String>) mapped;
        assertEquals("key1:value1", mappedTable.get("key1"));
        assertEquals("key2:value2", mappedTable.get("key2"));

        // Cleanup
        mappedTable.delete();
    }

    @Test
    void testFilter() {
        table.put("key1", "apple");
        table.put("key2", "banana");
        table.put("key3", "apricot");

        KTable<String, String> filtered = table.filter((key, value) -> value.startsWith("a"));
        assertTrue(filtered instanceof RedisKTable);

        RedisKTable<String, String> filteredTable = (RedisKTable<String, String>) filtered;
        assertEquals(2, filteredTable.size());
        assertEquals("apple", filteredTable.get("key1"));
        assertEquals("apricot", filteredTable.get("key3"));
        assertNull(filteredTable.get("key2"));

        // Cleanup
        filteredTable.delete();
    }

    @Test
    void testJoin() {
        RedisKTable<String, String> table2 = new RedisKTable<>(
            redissonClient, TABLE_NAME + "-2", String.class, String.class
        );

        try {
            table.put("key1", "value1");
            table.put("key2", "value2");
            table.put("key3", "value3");

            table2.put("key1", "other1");
            table2.put("key2", "other2");
            // key3 not in table2

            KTable<String, String> joined = table.join(table2, (v1, v2) -> v1 + "+" + v2);
            assertTrue(joined instanceof RedisKTable);

            RedisKTable<String, String> joinedTable = (RedisKTable<String, String>) joined;
            assertEquals(2, joinedTable.size()); // Only key1 and key2
            assertEquals("value1+other1", joinedTable.get("key1"));
            assertEquals("value2+other2", joinedTable.get("key2"));
            assertNull(joinedTable.get("key3")); // key3 not in joined result

            // Cleanup
            joinedTable.delete();
        } finally {
            table2.delete();
        }
    }

    @Test
    void testLeftJoin() {
        RedisKTable<String, String> table2 = new RedisKTable<>(
            redissonClient, TABLE_NAME + "-2", String.class, String.class
        );

        try {
            table.put("key1", "value1");
            table.put("key2", "value2");
            table.put("key3", "value3");

            table2.put("key1", "other1");
            table2.put("key2", "other2");
            // key3 not in table2

            KTable<String, String> joined = table.leftJoin(table2, (v1, v2) ->
                v1 + "+" + (v2 != null ? v2 : "null")
            );
            assertTrue(joined instanceof RedisKTable);

            RedisKTable<String, String> joinedTable = (RedisKTable<String, String>) joined;
            assertEquals(3, joinedTable.size()); // All keys from left table
            assertEquals("value1+other1", joinedTable.get("key1"));
            assertEquals("value2+other2", joinedTable.get("key2"));
            assertEquals("value3+null", joinedTable.get("key3")); // key3 with null from right

            // Cleanup
            joinedTable.delete();
        } finally {
            table2.delete();
        }
    }

    @Test
    void testJoinWithInMemoryRightTable() {
        table.put("key1", "value1");
        table.put("key2", "value2");
        table.put("key3", "value3");

        InMemoryKTable<String, String> right = new InMemoryKTable<>();
        right.put("key1", "other1");
        right.put("key2", "other2");
        right.put("key4", "other4");

        KTable<String, String> joined = table.join(right, (v1, v2) -> v1 + "+" + v2);
        assertTrue(joined instanceof RedisKTable);

        RedisKTable<String, String> joinedTable = (RedisKTable<String, String>) joined;
        assertEquals(2, joinedTable.size());
        assertEquals("value1+other1", joinedTable.get("key1"));
        assertEquals("value2+other2", joinedTable.get("key2"));
        assertNull(joinedTable.get("key3"));
        assertNull(joinedTable.get("key4"));

        joinedTable.delete();
    }

    @Test
    void testLeftJoinWithInMemoryRightTable() {
        table.put("key1", "value1");
        table.put("key2", "value2");
        table.put("key3", "value3");

        InMemoryKTable<String, String> right = new InMemoryKTable<>();
        right.put("key1", "other1");
        right.put("key2", "other2");

        KTable<String, String> joined = table.leftJoin(right, (v1, v2) -> v1 + "+" + (v2 == null ? "null" : v2));
        assertTrue(joined instanceof RedisKTable);

        RedisKTable<String, String> joinedTable = (RedisKTable<String, String>) joined;
        assertEquals(3, joinedTable.size());
        assertEquals("value1+other1", joinedTable.get("key1"));
        assertEquals("value2+other2", joinedTable.get("key2"));
        assertEquals("value3+null", joinedTable.get("key3"));

        joinedTable.delete();
    }

    @Test
    void testInMemoryJoinWithRedisRightTable() {
        InMemoryKTable<String, String> left = new InMemoryKTable<>();
        left.put("key1", "value1");
        left.put("key2", "value2");
        left.put("key3", "value3");

        table.put("key1", "other1");
        table.put("key2", "other2");
        table.put("key4", "other4");

        KTable<String, String> joined = left.join(table, (v1, v2) -> v1 + "+" + v2);
        assertTrue(joined instanceof InMemoryKTable);

        InMemoryKTable<String, String> joinedTable = (InMemoryKTable<String, String>) joined;
        assertEquals(2, joinedTable.size());
        assertEquals("value1+other1", joinedTable.get("key1"));
        assertEquals("value2+other2", joinedTable.get("key2"));
        assertNull(joinedTable.get("key3"));
        assertNull(joinedTable.get("key4"));
    }

    @Test
    void testInMemoryLeftJoinWithRedisRightTable() {
        InMemoryKTable<String, String> left = new InMemoryKTable<>();
        left.put("key1", "value1");
        left.put("key2", "value2");
        left.put("key3", "value3");

        table.put("key1", "other1");
        table.put("key2", "other2");

        KTable<String, String> joined = left.leftJoin(table, (v1, v2) -> v1 + "+" + (v2 == null ? "null" : v2));
        assertTrue(joined instanceof InMemoryKTable);

        InMemoryKTable<String, String> joinedTable = (InMemoryKTable<String, String>) joined;
        assertEquals(3, joinedTable.size());
        assertEquals("value1+other1", joinedTable.get("key1"));
        assertEquals("value2+other2", joinedTable.get("key2"));
        assertEquals("value3+null", joinedTable.get("key3"));
    }

    @Test
    void testPersistence() {
        table.put("key1", "value1");
        table.put("key2", "value2");

        // Create a new instance pointing to the same Redis hash
        RedisKTable<String, String> table2 = new RedisKTable<>(
            redissonClient, TABLE_NAME, String.class, String.class
        );

        // Should see the same data
        assertEquals("value1", table2.get("key1"));
        assertEquals("value2", table2.get("key2"));
        assertEquals(2, table2.size());
    }
}
