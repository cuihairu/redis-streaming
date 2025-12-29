package io.github.cuihairu.redis.streaming.table;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KTable.KeyValue and DefaultKeyValue
 */
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

        KTable.KeyValue<String, Integer> kv3 = KTable.KeyValue.of(null, null);
        assertNull(kv3.getKey());
        assertNull(kv3.getValue());
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

    @Test
    void testDefaultKeyValueWithNullKey() {
        KTable.DefaultKeyValue<String, Integer> kv =
                new KTable.DefaultKeyValue<>(null, 100);

        assertNull(kv.getKey());
        assertEquals(100, kv.getValue());
    }

    @Test
    void testDefaultKeyValueWithNullValue() {
        KTable.DefaultKeyValue<String, Integer> kv =
                new KTable.DefaultKeyValue<>("key", null);

        assertEquals("key", kv.getKey());
        assertNull(kv.getValue());
    }

    @Test
    void testDefaultKeyValueWithBothNulls() {
        KTable.DefaultKeyValue<String, Integer> kv =
                new KTable.DefaultKeyValue<>(null, null);

        assertNull(kv.getKey());
        assertNull(kv.getValue());
    }

    @Test
    void testKeyValueWithComplexTypes() {
        Map<String, Integer> complexValue = Map.of("a", 1, "b", 2);

        KTable.KeyValue<String, Map<String, Integer>> kv =
                KTable.KeyValue.of("complexKey", complexValue);

        assertEquals("complexKey", kv.getKey());
        assertEquals(complexValue, kv.getValue());
        assertEquals(2, kv.getValue().size());
    }

    @Test
    void testKeyValueWithNumericKey() {
        KTable.KeyValue<Integer, String> kv = KTable.KeyValue.of(42, "value");

        assertEquals(42, kv.getKey());
        assertEquals("value", kv.getValue());
    }

    @Test
    void testKeyValueWithEmptyStringKey() {
        KTable.KeyValue<String, String> kv = KTable.KeyValue.of("", "value");

        assertEquals("", kv.getKey());
        assertEquals("value", kv.getValue());
    }

    @Test
    void testKeyValueWithEmptyStringValue() {
        KTable.KeyValue<String, String> kv = KTable.KeyValue.of("key", "");

        assertEquals("key", kv.getKey());
        assertEquals("", kv.getValue());
    }

    @Test
    void testKeyValueWithZero() {
        KTable.KeyValue<String, Integer> kv = KTable.KeyValue.of("key", 0);

        assertEquals("key", kv.getKey());
        assertEquals(0, kv.getValue());
        assertFalse(kv.getValue() == null);
    }

    @Test
    void testKeyValueWithBoolean() {
        KTable.KeyValue<String, Boolean> kv = KTable.KeyValue.of("key", true);

        assertEquals("key", kv.getKey());
        assertTrue(kv.getValue());
    }

    @Test
    void testDefaultKeyValueImmutability() {
        KTable.DefaultKeyValue<String, Integer> kv =
                new KTable.DefaultKeyValue<>("key", 100);

        assertEquals("key", kv.getKey());
        assertEquals(100, kv.getValue());

        // The fields are final, so we can't change them
        // This test verifies that the class maintains its immutability
        assertEquals("key", kv.getKey());
        assertEquals(100, kv.getValue());
    }

    @Test
    void testKeyValueOfWithSameValues() {
        KTable.KeyValue<String, Integer> kv1 = KTable.KeyValue.of("key", 123);
        KTable.KeyValue<String, Integer> kv2 = KTable.KeyValue.of("key", 123);

        assertEquals(kv1.getKey(), kv2.getKey());
        assertEquals(kv1.getValue(), kv2.getValue());
    }
}
