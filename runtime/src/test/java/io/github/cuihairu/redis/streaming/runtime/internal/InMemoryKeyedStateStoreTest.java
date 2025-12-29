package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryKeyedStateStore
 */
class InMemoryKeyedStateStoreTest {

    private InMemoryKeyedStateStore<String> store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKeyedStateStore<>();
    }

    @Test
    void testInitialState() {
        assertNull(store.currentKey());
    }

    @Test
    void testSetCurrentKey() {
        store.setCurrentKey("key1");
        assertEquals("key1", store.currentKey());

        store.setCurrentKey("key2");
        assertEquals("key2", store.currentKey());
    }

    @Test
    void testPutAndGet() {
        store.put("state1", "key1", "value1");
        assertEquals("value1", store.get("state1", "key1"));
    }

    @Test
    void testGetNonExistentKey() {
        assertNull(store.get("state1", "nonexistent"));
    }

    @Test
    void testGetNonExistentState() {
        store.put("state1", "key1", "value1");
        assertNull(store.get("nonexistent", "key1"));
    }

    @Test
    void testPutMultipleValues() {
        store.put("state1", "key1", "value1");
        store.put("state1", "key2", "value2");
        store.put("state1", "key3", "value3");

        assertEquals("value1", store.get("state1", "key1"));
        assertEquals("value2", store.get("state1", "key2"));
        assertEquals("value3", store.get("state1", "key3"));
    }

    @Test
    void testPutMultipleStates() {
        store.put("state1", "key1", "value1");
        store.put("state2", "key1", "value2");
        store.put("state3", "key1", "value3");

        assertEquals("value1", store.get("state1", "key1"));
        assertEquals("value2", store.get("state2", "key1"));
        assertEquals("value3", store.get("state3", "key1"));
    }

    @Test
    void testRemove() {
        store.put("state1", "key1", "value1");
        assertEquals("value1", store.get("state1", "key1"));

        store.remove("state1", "key1");
        assertNull(store.get("state1", "key1"));
    }

    @Test
    void testRemoveNonExistentKey() {
        store.put("state1", "key1", "value1");
        store.remove("state1", "nonexistent");

        assertEquals("value1", store.get("state1", "key1"));
    }

    @Test
    void testRemoveNonExistentState() {
        store.remove("nonexistent", "key1");
        // Should not throw exception
    }

    @Test
    void testOverwriteValue() {
        store.put("state1", "key1", "value1");
        assertEquals("value1", store.get("state1", "key1"));

        store.put("state1", "key1", "value2");
        assertEquals("value2", store.get("state1", "key1"));
    }

    @Test
    void testNullKey() {
        store.put("state1", null, "value1");
        assertEquals("value1", store.get("state1", null));

        store.setCurrentKey(null);
        assertNull(store.currentKey());
    }

    @Test
    void testNullValue() {
        store.put("state1", "key1", null);
        assertNull(store.get("state1", "key1"));
    }

    @Test
    void testComplexValueTypes() {
        Integer intValue = 123;
        Long longValue = 456L;
        Double doubleValue = 3.14;
        String stringValue = "test";

        store.put("intState", "key1", intValue);
        store.put("longState", "key1", longValue);
        store.put("doubleState", "key1", doubleValue);
        store.put("stringState", "key1", stringValue);

        assertEquals(intValue, store.get("intState", "key1"));
        assertEquals(longValue, store.get("longState", "key1"));
        assertEquals(doubleValue, store.get("doubleState", "key1"));
        assertEquals(stringValue, store.get("stringState", "key1"));
    }

    @Test
    void testDifferentKeyTypes() {
        InMemoryKeyedStateStore<Integer> intKeyStore = new InMemoryKeyedStateStore<>();

        intKeyStore.put("state1", 1, "value1");
        intKeyStore.put("state1", 2, "value2");
        intKeyStore.put("state1", 3, "value3");

        assertEquals("value1", intKeyStore.get("state1", 1));
        assertEquals("value2", intKeyStore.get("state1", 2));
        assertEquals("value3", intKeyStore.get("state1", 3));
    }

    @Test
    void testManyKeys() {
        for (int i = 0; i < 1000; i++) {
            store.put("state1", "key" + i, "value" + i);
        }

        for (int i = 0; i < 1000; i++) {
            assertEquals("value" + i, store.get("state1", "key" + i));
        }
    }

    @Test
    void testManyStates() {
        for (int i = 0; i < 100; i++) {
            store.put("state" + i, "key1", "value" + i);
        }

        for (int i = 0; i < 100; i++) {
            assertEquals("value" + i, store.get("state" + i, "key1"));
        }
    }

    @Test
    void testRemoveAndAddAgain() {
        store.put("state1", "key1", "value1");
        store.remove("state1", "key1");
        assertNull(store.get("state1", "key1"));

        store.put("state1", "key1", "value2");
        assertEquals("value2", store.get("state1", "key1"));
    }

    @Test
    void testIndependentStates() {
        store.put("state1", "key1", "value1");
        store.put("state2", "key1", "value2");

        store.remove("state1", "key1");

        assertNull(store.get("state1", "key1"));
        assertEquals("value2", store.get("state2", "key1"));
    }
}
