package io.github.cuihairu.redis.streaming.cdc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChangeEvent
 */
class ChangeEventTest {

    @Test
    void testNoArgsConstructor() {
        // Given & When
        ChangeEvent event = new ChangeEvent();

        // Then
        assertNotNull(event);
        assertNull(event.getEventType());
        assertNull(event.getDatabase());
        assertNull(event.getTable());
        assertNull(event.getKey());
        assertNull(event.getBeforeData());
        assertNull(event.getAfterData());
        assertNull(event.getTimestamp());
        assertNull(event.getTransactionId());
        assertNull(event.getPosition());
        assertNull(event.getMetadata());
        assertNull(event.getSource());
    }

    @Test
    void testAllArgsConstructor() {
        // Given
        ChangeEvent.EventType eventType = ChangeEvent.EventType.INSERT;
        String database = "test_db";
        String table = "test_table";
        String key = "1";
        Map<String, Object> beforeData = new HashMap<>();
        Map<String, Object> afterData = new HashMap<>();
        afterData.put("id", 1);
        afterData.put("name", "test");
        Instant timestamp = Instant.now();
        String transactionId = "tx-123";
        String position = "binlog.000001:100";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("server_id", 1);
        String source = "mysql-binlog";

        // When
        ChangeEvent event = new ChangeEvent(
            eventType, database, table, key, beforeData, afterData,
            timestamp, transactionId, position, metadata, source
        );

        // Then
        assertEquals(eventType, event.getEventType());
        assertEquals(database, event.getDatabase());
        assertEquals(table, event.getTable());
        assertEquals(key, event.getKey());
        assertEquals(beforeData, event.getBeforeData());
        assertEquals(afterData, event.getAfterData());
        assertEquals(timestamp, event.getTimestamp());
        assertEquals(transactionId, event.getTransactionId());
        assertEquals(position, event.getPosition());
        assertEquals(metadata, event.getMetadata());
        assertEquals(source, event.getSource());
    }

    @Test
    void testSimpleConstructor() {
        // Given
        ChangeEvent.EventType eventType = ChangeEvent.EventType.INSERT;
        String database = "test_db";
        String table = "test_table";
        Map<String, Object> afterData = new HashMap<>();
        afterData.put("id", 1);

        // When
        ChangeEvent event = new ChangeEvent(eventType, database, table, afterData);

        // Then
        assertEquals(eventType, event.getEventType());
        assertEquals(database, event.getDatabase());
        assertEquals(table, event.getTable());
        assertEquals(afterData, event.getAfterData());
        assertNotNull(event.getTimestamp());
        assertNull(event.getKey());
        assertNull(event.getBeforeData());
    }

    @Test
    void testConstructorWithBeforeData() {
        // Given
        ChangeEvent.EventType eventType = ChangeEvent.EventType.UPDATE;
        String database = "test_db";
        String table = "test_table";
        String key = "1";
        Map<String, Object> beforeData = new HashMap<>();
        beforeData.put("name", "old");
        Map<String, Object> afterData = new HashMap<>();
        afterData.put("name", "new");

        // When
        ChangeEvent event = new ChangeEvent(eventType, database, table, key, beforeData, afterData);

        // Then
        assertEquals(eventType, event.getEventType());
        assertEquals(key, event.getKey());
        assertEquals(beforeData, event.getBeforeData());
        assertEquals(afterData, event.getAfterData());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void testIsDataChangeWithInsert() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.INSERT);

        // When & Then
        assertTrue(event.isDataChange());
    }

    @Test
    void testIsDataChangeWithUpdate() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.UPDATE);

        // When & Then
        assertTrue(event.isDataChange());
    }

    @Test
    void testIsDataChangeWithDelete() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.DELETE);

        // When & Then
        assertTrue(event.isDataChange());
    }

    @Test
    void testIsDataChangeWithSchemaChange() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.SCHEMA_CHANGE);

        // When & Then
        assertFalse(event.isDataChange());
    }

    @Test
    void testIsDataChangeWithTransactionBegin() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.TRANSACTION_BEGIN);

        // When & Then
        assertFalse(event.isDataChange());
    }

    @Test
    void testIsSchemaChangeWithSchemaChange() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.SCHEMA_CHANGE);

        // When & Then
        assertTrue(event.isSchemaChange());
    }

    @Test
    void testIsSchemaChangeWithInsert() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.INSERT);

        // When & Then
        assertFalse(event.isSchemaChange());
    }

    @Test
    void testIsTransactionEventWithTransactionBegin() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.TRANSACTION_BEGIN);

        // When & Then
        assertTrue(event.isTransactionEvent());
    }

    @Test
    void testIsTransactionEventWithTransactionCommit() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.TRANSACTION_COMMIT);

        // When & Then
        assertTrue(event.isTransactionEvent());
    }

    @Test
    void testIsTransactionEventWithInsert() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.INSERT);

        // When & Then
        assertFalse(event.isTransactionEvent());
    }

    @Test
    void testGetFullTableName() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setDatabase("mydb");
        event.setTable("mytable");

        // When
        String fullTableName = event.getFullTableName();

        // Then
        assertEquals("mydb.mytable", fullTableName);
    }

    @Test
    void testEventTypeEnumValues() {
        // Given & When
        ChangeEvent.EventType[] values = ChangeEvent.EventType.values();

        // Then
        assertEquals(7, values.length);
        assertEquals(ChangeEvent.EventType.INSERT, values[0]);
        assertEquals(ChangeEvent.EventType.UPDATE, values[1]);
        assertEquals(ChangeEvent.EventType.DELETE, values[2]);
        assertEquals(ChangeEvent.EventType.SCHEMA_CHANGE, values[3]);
        assertEquals(ChangeEvent.EventType.TRANSACTION_BEGIN, values[4]);
        assertEquals(ChangeEvent.EventType.TRANSACTION_COMMIT, values[5]);
        assertEquals(ChangeEvent.EventType.HEARTBEAT, values[6]);
    }

    @Test
    void testEventTypeValueOf() {
        // Given & When
        ChangeEvent.EventType insert = ChangeEvent.EventType.valueOf("INSERT");
        ChangeEvent.EventType update = ChangeEvent.EventType.valueOf("UPDATE");
        ChangeEvent.EventType delete = ChangeEvent.EventType.valueOf("DELETE");
        ChangeEvent.EventType schemaChange = ChangeEvent.EventType.valueOf("SCHEMA_CHANGE");
        ChangeEvent.EventType heartbeat = ChangeEvent.EventType.valueOf("HEARTBEAT");

        // Then
        assertEquals(ChangeEvent.EventType.INSERT, insert);
        assertEquals(ChangeEvent.EventType.UPDATE, update);
        assertEquals(ChangeEvent.EventType.DELETE, delete);
        assertEquals(ChangeEvent.EventType.SCHEMA_CHANGE, schemaChange);
        assertEquals(ChangeEvent.EventType.HEARTBEAT, heartbeat);
    }

    @Test
    void testSettersAndGetters() {
        // Given
        ChangeEvent event = new ChangeEvent();
        ChangeEvent.EventType eventType = ChangeEvent.EventType.INSERT;
        String database = "db";
        String table = "table";
        String key = "key";
        Map<String, Object> beforeData = new HashMap<>();
        Map<String, Object> afterData = new HashMap<>();
        Instant timestamp = Instant.now();
        String transactionId = "tx-id";
        String position = "position";
        Map<String, Object> metadata = new HashMap<>();
        String source = "source";

        // When
        event.setEventType(eventType);
        event.setDatabase(database);
        event.setTable(table);
        event.setKey(key);
        event.setBeforeData(beforeData);
        event.setAfterData(afterData);
        event.setTimestamp(timestamp);
        event.setTransactionId(transactionId);
        event.setPosition(position);
        event.setMetadata(metadata);
        event.setSource(source);

        // Then
        assertEquals(eventType, event.getEventType());
        assertEquals(database, event.getDatabase());
        assertEquals(table, event.getTable());
        assertEquals(key, event.getKey());
        assertEquals(beforeData, event.getBeforeData());
        assertEquals(afterData, event.getAfterData());
        assertEquals(timestamp, event.getTimestamp());
        assertEquals(transactionId, event.getTransactionId());
        assertEquals(position, event.getPosition());
        assertEquals(metadata, event.getMetadata());
        assertEquals(source, event.getSource());
    }

    @Test
    void testEqualsAndHashCode() {
        // Given
        ChangeEvent event1 = new ChangeEvent();
        event1.setEventType(ChangeEvent.EventType.INSERT);
        event1.setDatabase("db");
        event1.setTable("table");

        ChangeEvent event2 = new ChangeEvent();
        event2.setEventType(ChangeEvent.EventType.INSERT);
        event2.setDatabase("db");
        event2.setTable("table");

        ChangeEvent event3 = new ChangeEvent();
        event3.setEventType(ChangeEvent.EventType.UPDATE);
        event3.setDatabase("db");
        event3.setTable("table");

        // Then
        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
        assertNotEquals(event1, event3);
    }

    @Test
    void testWithNullDatabase() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setTable("table");

        // When & Then - should handle null database
        assertEquals("null.table", event.getFullTableName());
    }

    @Test
    void testWithNullTable() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setDatabase("db");

        // When & Then - should handle null table
        assertEquals("db.null", event.getFullTableName());
    }

    @Test
    void testWithBothNullDatabaseAndTable() {
        // Given
        ChangeEvent event = new ChangeEvent();

        // When & Then - should handle both null
        assertEquals("null.null", event.getFullTableName());
    }

    @Test
    void testWithComplexData() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("id", 1);
        data.put("name", "test");
        data.put("active", true);
        data.put("score", 99.5);
        data.put("nested", Map.of("key", "value"));

        // When
        ChangeEvent event = new ChangeEvent(
            ChangeEvent.EventType.INSERT, "db", "table", data
        );

        // Then
        assertEquals(data, event.getAfterData());
        assertEquals(5, event.getAfterData().size());
    }

    @Test
    void testWithHeartbeatEvent() {
        // Given
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.HEARTBEAT);

        // When & Then
        assertFalse(event.isDataChange());
        assertFalse(event.isSchemaChange());
        assertFalse(event.isTransactionEvent());
    }

    @Test
    void testWithEmptyMaps() {
        // Given
        Map<String, Object> emptyMap = new HashMap<>();

        // When
        ChangeEvent event = new ChangeEvent(
            ChangeEvent.EventType.UPDATE, "db", "table", "key",
            emptyMap, emptyMap
        );

        // Then
        assertTrue(event.getBeforeData().isEmpty());
        assertTrue(event.getAfterData().isEmpty());
    }
}
