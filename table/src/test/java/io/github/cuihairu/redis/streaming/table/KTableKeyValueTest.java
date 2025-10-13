package io.github.cuihairu.redis.streaming.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KTableKeyValueTest {

    @Test
    void testKeyValueCreation() {
        KTable.KeyValue<String, Integer> kv = KTable.KeyValue.of("key", 123);

        assertEquals("key", kv.getKey());
        assertEquals(123, kv.getValue());
    }

    @Test
    void testKeyValueWithNulls() {
        KTable.KeyValue<String, Integer> kv1 = KTable.KeyValue.of(null, 123);
        assertNull(kv1.getKey());
        assertEquals(123, kv1.getValue());

        KTable.KeyValue<String, Integer> kv2 = KTable.KeyValue.of("key", null);
        assertEquals("key", kv2.getKey());
        assertNull(kv2.getValue());
    }

    @Test
    void testKeyValueToString() {
        KTable.KeyValue<String, Integer> kv = KTable.KeyValue.of("test", 42);
        String str = kv.toString();

        assertTrue(str.contains("test"));
        assertTrue(str.contains("42"));
    }

    @Test
    void testDefaultKeyValue() {
        KTable.DefaultKeyValue<String, Integer> kv =
                new KTable.DefaultKeyValue<>("key", 100);

        assertEquals("key", kv.getKey());
        assertEquals(100, kv.getValue());
    }
}
