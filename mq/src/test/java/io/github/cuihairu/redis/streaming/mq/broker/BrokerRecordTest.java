package io.github.cuihairu.redis.streaming.mq.broker;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BrokerRecord
 */
class BrokerRecordTest {

    @Test
    void testConstructor() {
        Map<String, Object> data = new HashMap<>();
        data.put("key1", "value1");
        data.put("key2", 123);

        BrokerRecord record = new BrokerRecord("test-id", data);

        assertEquals("test-id", record.getId());
        assertEquals(data, record.getData());
    }

    @Test
    void testConstructorWithNullId() {
        Map<String, Object> data = new HashMap<>();
        BrokerRecord record = new BrokerRecord(null, data);

        assertNull(record.getId());
        assertEquals(data, record.getData());
    }

    @Test
    void testConstructorWithNullData() {
        BrokerRecord record = new BrokerRecord("id1", null);

        assertEquals("id1", record.getId());
        assertNull(record.getData());
    }

    @Test
    void testGetId() {
        BrokerRecord record = new BrokerRecord("abc-123", new HashMap<>());

        assertEquals("abc-123", record.getId());
    }

    @Test
    void testGetData() {
        Map<String, Object> data = new HashMap<>();
        data.put("field1", "value1");
        data.put("field2", 456);

        BrokerRecord record = new BrokerRecord("id", data);

        assertEquals(data, record.getData());
        assertEquals(2, record.getData().size());
        assertEquals("value1", record.getData().get("field1"));
        assertEquals(456, record.getData().get("field2"));
    }

    @Test
    void testWithComplexData() {
        Map<String, Object> complexData = new HashMap<>();
        complexData.put("string", "text");
        complexData.put("number", 123);
        complexData.put("decimal", 45.67);
        complexData.put("boolean", true);
        complexData.put("nested", Map.of("inner", "value"));

        BrokerRecord record = new BrokerRecord("complex-id", complexData);

        assertEquals(5, record.getData().size());
        assertEquals("text", record.getData().get("string"));
        assertEquals(123, record.getData().get("number"));
        assertEquals(45.67, record.getData().get("decimal"));
        assertEquals(true, record.getData().get("boolean"));
    }

    @Test
    void testWithNullValuesInData() {
        Map<String, Object> data = new HashMap<>();
        data.put("key1", null);
        data.put("key2", "value2");

        BrokerRecord record = new BrokerRecord("id", data);

        assertNull(record.getData().get("key1"));
        assertEquals("value2", record.getData().get("key2"));
    }

    @Test
    void testMultipleBrokerRecords() {
        Map<String, Object> data1 = Map.of("key", "value1");
        Map<String, Object> data2 = Map.of("key", "value2");

        BrokerRecord record1 = new BrokerRecord("id1", data1);
        BrokerRecord record2 = new BrokerRecord("id2", data2);

        assertEquals("id1", record1.getId());
        assertEquals("id2", record2.getId());
        assertEquals("value1", record1.getData().get("key"));
        assertEquals("value2", record2.getData().get("key"));
    }

    @Test
    void testWithSpecialCharactersInId() {
        String specialId = "test-id-with:colons-and/slashes";

        BrokerRecord record = new BrokerRecord(specialId, new HashMap<>());

        assertEquals(specialId, record.getId());
    }

    @Test
    void testDataCanContainDifferentTypes() {
        Map<String, Object> mixedData = new HashMap<>();
        mixedData.put("string", "text");
        mixedData.put("integer", 123);
        mixedData.put("long", 123456789L);
        mixedData.put("double", 123.456);
        mixedData.put("boolean", true);
        mixedData.put("nullValue", null);

        BrokerRecord record = new BrokerRecord("mixed-id", mixedData);

        assertEquals("text", record.getData().get("string"));
        assertEquals(123, record.getData().get("integer"));
        assertEquals(123456789L, record.getData().get("long"));
        assertEquals(123.456, record.getData().get("double"));
        assertEquals(true, record.getData().get("boolean"));
        assertNull(record.getData().get("nullValue"));
    }

    @Test
    void testEmptyStringId() {
        BrokerRecord record = new BrokerRecord("", new HashMap<>());

        assertEquals("", record.getId());
        assertTrue(record.getData().isEmpty());
    }

    @Test
    void testLargeDataMap() {
        Map<String, Object> largeData = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            largeData.put("key" + i, "value" + i);
        }

        BrokerRecord record = new BrokerRecord("large-id", largeData);

        assertEquals(100, record.getData().size());
        assertEquals("value0", record.getData().get("key0"));
        assertEquals("value99", record.getData().get("key99"));
    }

    @Test
    void testImmutableId() {
        BrokerRecord record = new BrokerRecord("original-id", new HashMap<>());

        // The id field is final, so it cannot be changed
        assertEquals("original-id", record.getId());
    }

    @Test
    void testImmutableDataReference() {
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");

        BrokerRecord record = new BrokerRecord("id", data);

        // Modify original map - the record still references the same map
        data.put("newKey", "newValue");
        assertEquals("newValue", record.getData().get("newKey"));
    }
}
