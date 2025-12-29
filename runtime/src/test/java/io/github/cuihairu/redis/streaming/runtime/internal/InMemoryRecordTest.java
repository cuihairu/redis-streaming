package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryRecord
 */
class InMemoryRecordTest {

    @Test
    void testConstructor() {
        InMemoryRecord<String> record = new InMemoryRecord<>("value", 1000L);

        assertEquals("value", record.value());
        assertEquals(1000L, record.timestamp());
    }

    @Test
    void testWithNullValue() {
        InMemoryRecord<String> record = new InMemoryRecord<>(null, 1000L);

        assertNull(record.value());
        assertEquals(1000L, record.timestamp());
    }

    @Test
    void testWithNegativeTimestamp() {
        InMemoryRecord<String> record = new InMemoryRecord<>("value", -1000L);

        assertEquals("value", record.value());
        assertEquals(-1000L, record.timestamp());
    }

    @Test
    void testWithZeroTimestamp() {
        InMemoryRecord<String> record = new InMemoryRecord<>("value", 0L);

        assertEquals("value", record.value());
        assertEquals(0L, record.timestamp());
    }

    @Test
    void testWithLargeTimestamp() {
        long largeTimestamp = Long.MAX_VALUE;
        InMemoryRecord<String> record = new InMemoryRecord<>("value", largeTimestamp);

        assertEquals("value", record.value());
        assertEquals(largeTimestamp, record.timestamp());
    }

    @Test
    void testDifferentValueTypes() {
        InMemoryRecord<Integer> intRecord = new InMemoryRecord<>(123, 1000L);
        InMemoryRecord<Long> longRecord = new InMemoryRecord<>(456L, 1000L);
        InMemoryRecord<Double> doubleRecord = new InMemoryRecord<>(3.14, 1000L);
        InMemoryRecord<Boolean> boolRecord = new InMemoryRecord<>(true, 1000L);

        assertEquals(123, intRecord.value());
        assertEquals(456L, longRecord.value());
        assertEquals(3.14, doubleRecord.value());
        assertTrue(boolRecord.value());
    }

    @Test
    void testRecordEquality() {
        InMemoryRecord<String> r1 = new InMemoryRecord<>("value", 1000L);
        InMemoryRecord<String> r2 = new InMemoryRecord<>("value", 1000L);

        assertEquals(r1.value(), r2.value());
        assertEquals(r1.timestamp(), r2.timestamp());
    }
}
