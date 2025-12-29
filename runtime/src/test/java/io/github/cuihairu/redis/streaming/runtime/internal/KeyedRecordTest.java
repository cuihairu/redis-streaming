package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KeyedRecord
 */
class KeyedRecordTest {

    @Test
    void testConstructor() {
        KeyedRecord<String, String> record = new KeyedRecord<>("key1", "value1", 1000L);

        assertEquals("key1", record.key());
        assertEquals("value1", record.value());
        assertEquals(1000L, record.timestamp());
    }

    @Test
    void testWithNullKey() {
        KeyedRecord<String, String> record = new KeyedRecord<>(null, "value1", 1000L);

        assertNull(record.key());
        assertEquals("value1", record.value());
        assertEquals(1000L, record.timestamp());
    }

    @Test
    void testWithNullValue() {
        KeyedRecord<String, String> record = new KeyedRecord<>("key1", null, 1000L);

        assertEquals("key1", record.key());
        assertNull(record.value());
        assertEquals(1000L, record.timestamp());
    }

    @Test
    void testWithNegativeTimestamp() {
        KeyedRecord<String, String> record = new KeyedRecord<>("key1", "value1", -1000L);

        assertEquals("key1", record.key());
        assertEquals("value1", record.value());
        assertEquals(-1000L, record.timestamp());
    }

    @Test
    void testWithZeroTimestamp() {
        KeyedRecord<String, String> record = new KeyedRecord<>("key1", "value1", 0L);

        assertEquals("key1", record.key());
        assertEquals("value1", record.value());
        assertEquals(0L, record.timestamp());
    }

    @Test
    void testDifferentKeyTypes() {
        KeyedRecord<Integer, String> intKeyRecord = new KeyedRecord<>(123, "value1", 1000L);
        KeyedRecord<Long, String> longKeyRecord = new KeyedRecord<>(456L, "value2", 1000L);

        assertEquals(123, intKeyRecord.key());
        assertEquals("value1", intKeyRecord.value());
        assertEquals(456L, longKeyRecord.key());
        assertEquals("value2", longKeyRecord.value());
    }

    @Test
    void testDifferentValueTypes() {
        KeyedRecord<String, Integer> intValRecord = new KeyedRecord<>("key1", 123, 1000L);
        KeyedRecord<String, Long> longValRecord = new KeyedRecord<>("key2", 456L, 1000L);
        KeyedRecord<String, Double> doubleValRecord = new KeyedRecord<>("key3", 3.14, 1000L);
        KeyedRecord<String, Boolean> boolValRecord = new KeyedRecord<>("key4", true, 1000L);

        assertEquals(123, intValRecord.value());
        assertEquals(456L, longValRecord.value());
        assertEquals(3.14, doubleValRecord.value());
        assertTrue(boolValRecord.value());
    }

    @Test
    void testComplexKeyValueTypes() {
        KeyedRecord<String[], String> record = new KeyedRecord<>(new String[]{"a", "b"}, "value", 1000L);

        assertNotNull(record.key());
        assertEquals(2, record.key().length);
        assertEquals("a", record.key()[0]);
        assertEquals("value", record.value());
    }
}
