package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageEntry
 */
class MessageEntryTest {

    @Test
    void testBuilder() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("key1", "value1");
        fields.put("key2", 123);

        MessageEntry entry = MessageEntry.builder()
                .id("msg-123")
                .partitionId(0)
                .fields(fields)
                .build();

        assertEquals("msg-123", entry.getId());
        assertEquals(0, entry.getPartitionId());
        assertEquals(fields, entry.getFields());
    }

    @Test
    void testBuilderWithPartialFields() {
        MessageEntry entry = MessageEntry.builder()
                .id("partial-msg")
                .build();

        assertEquals("partial-msg", entry.getId());
        assertEquals(0, entry.getPartitionId());
        assertNull(entry.getFields());
    }

    @Test
    void testSettersAndGetters() {
        MessageEntry entry = MessageEntry.builder().build();
        Map<String, Object> fields = new HashMap<>();
        fields.put("field", "value");

        entry.setId("new-id");
        entry.setPartitionId(5);
        entry.setFields(fields);

        assertEquals("new-id", entry.getId());
        assertEquals(5, entry.getPartitionId());
        assertEquals(fields, entry.getFields());
    }

    @Test
    void testWithNullValues() {
        MessageEntry entry = MessageEntry.builder()
                .id(null)
                .partitionId(0)
                .fields(null)
                .build();

        assertNull(entry.getId());
        assertNull(entry.getFields());
        assertEquals(0, entry.getPartitionId());
    }

    @Test
    void testWithEmptyId() {
        MessageEntry entry = MessageEntry.builder()
                .id("")
                .build();

        assertEquals("", entry.getId());
    }

    @Test
    void testWithDifferentPartitionIds() {
        MessageEntry e1 = MessageEntry.builder().partitionId(0).build();
        MessageEntry e2 = MessageEntry.builder().partitionId(1).build();
        MessageEntry e3 = MessageEntry.builder().partitionId(10).build();
        MessageEntry e4 = MessageEntry.builder().partitionId(100).build();

        assertEquals(0, e1.getPartitionId());
        assertEquals(1, e2.getPartitionId());
        assertEquals(10, e3.getPartitionId());
        assertEquals(100, e4.getPartitionId());
    }

    @Test
    void testWithEmptyFields() {
        Map<String, Object> emptyFields = new HashMap<>();

        MessageEntry entry = MessageEntry.builder()
                .id("msg-with-empty-fields")
                .fields(emptyFields)
                .build();

        assertNotNull(entry.getFields());
        assertTrue(entry.getFields().isEmpty());
    }

    @Test
    void testWithNullFields() {
        MessageEntry entry = MessageEntry.builder()
                .id("msg-with-null-fields")
                .fields(null)
                .build();

        assertNull(entry.getFields());
    }

    @Test
    void testLombokDataAnnotation() {
        Map<String, Object> fields = Map.of("key", "value");

        MessageEntry e1 = MessageEntry.builder()
                .id("msg-1")
                .partitionId(0)
                .fields(fields)
                .build();

        MessageEntry e2 = MessageEntry.builder()
                .id("msg-1")
                .partitionId(0)
                .fields(fields)
                .build();

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void testWithComplexFields() {
        Map<String, Object> complexFields = new HashMap<>();
        complexFields.put("string", "text");
        complexFields.put("number", 123);
        complexFields.put("decimal", 45.67);
        complexFields.put("boolean", true);
        complexFields.put("nullValue", null);

        MessageEntry entry = MessageEntry.builder()
                .id("complex-msg")
                .fields(complexFields)
                .build();

        assertEquals(5, entry.getFields().size());
        assertEquals("text", entry.getFields().get("string"));
        assertEquals(123, entry.getFields().get("number"));
        assertEquals(45.67, entry.getFields().get("decimal"));
        assertEquals(true, entry.getFields().get("boolean"));
        assertNull(entry.getFields().get("nullValue"));
    }

    @Test
    void testWithDifferentFieldTypes() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("stringField", "stringValue");
        fields.put("intField", 42);
        fields.put("longField", 123456789L);
        fields.put("doubleField", 3.14);
        fields.put("boolField", false);

        MessageEntry entry = MessageEntry.builder()
                .id("multi-type-msg")
                .fields(fields)
                .build();

        assertEquals("stringValue", entry.getFields().get("stringField"));
        assertEquals(42, entry.getFields().get("intField"));
        assertEquals(123456789L, entry.getFields().get("longField"));
        assertEquals(3.14, entry.getFields().get("doubleField"));
        assertEquals(false, entry.getFields().get("boolField"));
    }

    @Test
    void testWithSpecialCharactersInId() {
        String specialId = "msg-with:colons-and/slashes";

        MessageEntry entry = MessageEntry.builder()
                .id(specialId)
                .build();

        assertEquals(specialId, entry.getId());
    }

    @Test
    void testWithNegativePartitionId() {
        // This might be an edge case, but testing for robustness
        MessageEntry entry = MessageEntry.builder()
                .partitionId(-1)
                .build();

        assertEquals(-1, entry.getPartitionId());
    }

    @Test
    void testFieldsModification() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("key1", "value1");

        MessageEntry entry = MessageEntry.builder()
                .id("msg-1")
                .fields(fields)
                .build();

        assertEquals("value1", entry.getFields().get("key1"));

        // Modify fields
        entry.getFields().put("key2", "value2");

        assertEquals("value2", entry.getFields().get("key2"));
        assertEquals(2, entry.getFields().size());
    }

    @Test
    void testMultipleMessageEntries() {
        Map<String, Object> fields1 = Map.of("key", "value1");
        Map<String, Object> fields2 = Map.of("key", "value2");

        MessageEntry e1 = MessageEntry.builder()
                .id("msg-1")
                .partitionId(0)
                .fields(fields1)
                .build();

        MessageEntry e2 = MessageEntry.builder()
                .id("msg-2")
                .partitionId(1)
                .fields(fields2)
                .build();

        assertEquals("msg-1", e1.getId());
        assertEquals("msg-2", e2.getId());
        assertEquals(0, e1.getPartitionId());
        assertEquals(1, e2.getPartitionId());
        assertEquals("value1", e1.getFields().get("key"));
        assertEquals("value2", e2.getFields().get("key"));
    }

    @Test
    void testWithLargeFieldsMap() {
        Map<String, Object> largeFields = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            largeFields.put("key" + i, "value" + i);
        }

        MessageEntry entry = MessageEntry.builder()
                .id("large-msg")
                .fields(largeFields)
                .build();

        assertEquals(100, entry.getFields().size());
        assertEquals("value0", entry.getFields().get("key0"));
        assertEquals("value99", entry.getFields().get("key99"));
    }

    @Test
    void testWithNestedFields() {
        Map<String, Object> nestedMap = new HashMap<>();
        nestedMap.put("innerKey", "innerValue");

        Map<String, Object> fields = new HashMap<>();
        fields.put("nested", nestedMap);

        MessageEntry entry = MessageEntry.builder()
                .id("nested-msg")
                .fields(fields)
                .build();

        assertNotNull(entry.getFields().get("nested"));
        assertEquals(nestedMap, entry.getFields().get("nested"));
    }

    @Test
    void testWithNullFieldValue() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("validKey", "validValue");
        fields.put("nullKey", null);

        MessageEntry entry = MessageEntry.builder()
                .id("msg-with-null-field")
                .fields(fields)
                .build();

        assertEquals("validValue", entry.getFields().get("validKey"));
        assertNull(entry.getFields().get("nullKey"));
    }

    @Test
    void testToString() {
        Map<String, Object> fields = Map.of("key", "value");

        MessageEntry entry = MessageEntry.builder()
                .id("test-msg")
                .partitionId(0)
                .fields(fields)
                .build();

        String str = entry.toString();
        assertTrue(str.contains("test-msg") || str.contains("0") || str.contains("key"));
    }

    @Test
    void testWithNumericId() {
        MessageEntry entry = MessageEntry.builder()
                .id("12345")
                .build();

        assertEquals("12345", entry.getId());
    }

    @Test
    void testWithUUIDLikeId() {
        String uuidId = "550e8400-e29b-41d4-a716-446655440000";

        MessageEntry entry = MessageEntry.builder()
                .id(uuidId)
                .build();

        assertEquals(uuidId, entry.getId());
    }
}
