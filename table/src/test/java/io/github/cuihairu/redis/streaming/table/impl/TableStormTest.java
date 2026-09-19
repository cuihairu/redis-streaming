package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Error-path storm for KTable implementations (in-memory + redis grouped). */
class TableStormTest {

    @Test
    void inMemoryTableStorm() {
        InMemoryKTable<String, Integer> t = new InMemoryKTable<>();
        t.put("a", 1);
        assertTrue(Storms.storm(t, null) >= 3);
    }

    @Test
    void redisKTableStormBothWays() {
        RedisKTable<String, Integer> happy = Storms.constructing(
                () -> new RedisKTable<>(Storms.deep(org.redisson.api.RedissonClient.class), "t-h", String.class, Integer.class));
        assertTrue(Storms.storm(happy, null) > 5);
        RedisKTable<String, Integer> failing = Storms.constructing(
                () -> new RedisKTable<>(Storms.exploding(org.redisson.api.RedissonClient.class), "t-f", String.class, Integer.class));
        assertTrue(Storms.storm(failing, null) > 5);
    }

    @Test
    void groupedTableAggregations() {
        RedisKTable<String, Integer> src = Storms.constructing(
                () -> new RedisKTable<>(Storms.deep(org.redisson.api.RedissonClient.class), "t-g", String.class, Integer.class));
        io.github.cuihairu.redis.streaming.table.KGroupedTable<String, Integer> grouped = src.groupBy(kv -> kv.getKey());
        assertNotNull(grouped.count());
        assertNotNull(grouped.reduce((a, b) -> a + b, (a, b) -> a - b));
        assertTrue(Storms.storm(grouped, null) >= 2);
    }
}
