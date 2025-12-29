package io.github.cuihairu.redis.streaming.cdc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ChangeEvent serialization/deserialization
 */
class ChangeEventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testCreateInsertEvent() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "test_db",
                "users",
                Map.of("id", 123, "name", "Alice", "email", "alice@example.com")
        );

        // Then
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.INSERT);
        assertThat(event.getDatabase()).isEqualTo("test_db");
        assertThat(event.getTable()).isEqualTo("users");
        assertThat(event.getAfterData()).isNotNull();
        assertThat(event.getAfterData().get("id")).isEqualTo(123);
        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void testCreateUpdateEventWithKey() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.UPDATE,
                "test_db",
                "products",
                "product-456",
                Map.of("id", 456, "name", "Old Name"),
                Map.of("id", 456, "name", "New Name", "price", 99.99)
        );

        // Then
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.UPDATE);
        assertThat(event.getKey()).isEqualTo("product-456");
        assertThat(event.getBeforeData()).isNotNull();
        assertThat(event.getAfterData()).isNotNull();
        assertThat(event.getBeforeData().get("name")).isEqualTo("Old Name");
        assertThat(event.getAfterData().get("name")).isEqualTo("New Name");
    }

    @Test
    void testCreateDeleteEvent() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.DELETE,
                "test_db",
                "orders",
                "order-789",
                Map.of("id", 789, "status", "cancelled"),
                null
        );

        // Then
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.DELETE);
        assertThat(event.getBeforeData()).isNotNull();
        assertThat(event.getAfterData()).isNull();
    }

    @Test
    void testGetFullTableName() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "mydb",
                "mytable",
                Map.of("id", 1)
        );

        // When
        String fullName = event.getFullTableName();

        // Then
        assertThat(fullName).isEqualTo("mydb.mytable");
    }

    @Test
    void testIsDataChange() {
        // Given
        ChangeEvent insertEvent = new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", Map.of());
        ChangeEvent updateEvent = new ChangeEvent(ChangeEvent.EventType.UPDATE, "db", "t", "k", Map.of(), Map.of());
        ChangeEvent deleteEvent = new ChangeEvent(ChangeEvent.EventType.DELETE, "db", "t", "k", Map.of(), null);
        ChangeEvent schemaChangeEvent = new ChangeEvent(ChangeEvent.EventType.SCHEMA_CHANGE, "db", "t", Map.of());

        // Then
        assertThat(insertEvent.isDataChange()).isTrue();
        assertThat(updateEvent.isDataChange()).isTrue();
        assertThat(deleteEvent.isDataChange()).isTrue();
        assertThat(schemaChangeEvent.isDataChange()).isFalse();
    }

    @Test
    void testIsSchemaChange() {
        // Given
        ChangeEvent schemaChangeEvent = new ChangeEvent(ChangeEvent.EventType.SCHEMA_CHANGE, "db", "t", Map.of());
        ChangeEvent insertEvent = new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", Map.of());

        // Then
        assertThat(schemaChangeEvent.isSchemaChange()).isTrue();
        assertThat(insertEvent.isSchemaChange()).isFalse();
    }

    @Test
    void testIsTransactionEvent() {
        // Given
        ChangeEvent beginEvent = new ChangeEvent(ChangeEvent.EventType.TRANSACTION_BEGIN, "db", "t", Map.of());
        ChangeEvent commitEvent = new ChangeEvent(ChangeEvent.EventType.TRANSACTION_COMMIT, "db", "t", Map.of());
        ChangeEvent insertEvent = new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", Map.of());

        // Then
        assertThat(beginEvent.isTransactionEvent()).isTrue();
        assertThat(commitEvent.isTransactionEvent()).isTrue();
        assertThat(insertEvent.isTransactionEvent()).isFalse();
    }

    @Test
    void testEqualsAndHashCode() {
        // Given - 创建相同的事件，使用相同的 timestamp
        java.time.Instant now = java.time.Instant.now();
        ChangeEvent event1 = new ChangeEvent();
        event1.setEventType(ChangeEvent.EventType.INSERT);
        event1.setDatabase("db");
        event1.setTable("table");
        event1.setKey("key");
        event1.setAfterData(Map.of("id", 1));
        event1.setTimestamp(now);

        ChangeEvent event2 = new ChangeEvent();
        event2.setEventType(ChangeEvent.EventType.INSERT);
        event2.setDatabase("db");
        event2.setTable("table");
        event2.setKey("key");
        event2.setAfterData(Map.of("id", 1));
        event2.setTimestamp(now);

        ChangeEvent event3 = new ChangeEvent();
        event3.setEventType(ChangeEvent.EventType.UPDATE);
        event3.setDatabase("db");
        event3.setTable("table");
        event3.setKey("key");
        event3.setAfterData(Map.of("id", 1));
        event3.setTimestamp(now);

        // Then
        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
        assertThat(event1).isNotEqualTo(event3);
    }

    @Test
    void testToString() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "test_db",
                "users",
                "user-123",
                null,
                Map.of("id", 123, "name", "Alice")
        );

        // When
        String str = event.toString();

        // Then
        assertThat(str).contains("INSERT");
        assertThat(str).contains("test_db");
        assertThat(str).contains("users");
        assertThat(str).contains("user-123");
    }

    @Test
    void testEventTypeValues() {
        // Then
        assertThat(ChangeEvent.EventType.INSERT).isNotNull();
        assertThat(ChangeEvent.EventType.UPDATE).isNotNull();
        assertThat(ChangeEvent.EventType.DELETE).isNotNull();
        assertThat(ChangeEvent.EventType.SCHEMA_CHANGE).isNotNull();
        assertThat(ChangeEvent.EventType.TRANSACTION_BEGIN).isNotNull();
        assertThat(ChangeEvent.EventType.TRANSACTION_COMMIT).isNotNull();
        assertThat(ChangeEvent.EventType.HEARTBEAT).isNotNull();
    }

    @Test
    void testComplexNestedData() {
        // Given
        Map<String, Object> complexData = Map.of(
                "user", Map.of(
                        "id", 1,
                        "profile", Map.of(
                                "age", 30,
                                "address", Map.of(
                                        "city", "New York",
                                        "zip", "10001"
                                )
                        )
                )
        );

        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "db",
                "table",
                complexData
        );

        // Then
        assertThat(event.getAfterData()).isEqualTo(complexData);
    }

    @Test
    void testNullValues() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                null,
                null,
                (Map<String, Object>) null
        );

        // When
        String str = event.toString();

        // Then - should not throw NPE
        assertThat(str).isNotNull();
    }

    @Test
    void testAllArgsConstructor() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "db",
                "table",
                "key",
                Map.of("old", 1),
                Map.of("new", 2),
                java.time.Instant.now(),
                "tx-123",
                "pos-456",
                Map.of("meta", "data"),
                "source"
        );

        // Then
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.INSERT);
        assertThat(event.getTransactionId()).isEqualTo("tx-123");
        assertThat(event.getPosition()).isEqualTo("pos-456");
        assertThat(event.getSource()).isEqualTo("source");
    }

    @Test
    void testNoArgsConstructor() {
        // When
        ChangeEvent event = new ChangeEvent();

        // Then
        assertThat(event.getEventType()).isNull();
        assertThat(event.getDatabase()).isNull();
        assertThat(event.getTable()).isNull();
    }

    @Test
    void testSettersAndGetters() {
        // Given
        ChangeEvent event = new ChangeEvent();

        // When
        event.setEventType(ChangeEvent.EventType.UPDATE);
        event.setDatabase("mydb");
        event.setTable("mytable");
        event.setKey("mykey");
        event.setBeforeData(Map.of("old", 1));
        event.setAfterData(Map.of("new", 2));

        // Then
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.UPDATE);
        assertThat(event.getDatabase()).isEqualTo("mydb");
        assertThat(event.getTable()).isEqualTo("mytable");
        assertThat(event.getKey()).isEqualTo("mykey");
        assertThat(event.getBeforeData()).isEqualTo(Map.of("old", 1));
        assertThat(event.getAfterData()).isEqualTo(Map.of("new", 2));
    }

    @Test
    void testLombokDataAnnotation() {
        // Given
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT,
                "db",
                "table",
                Map.of("id", 1)
        );

        // When
        event.setKey("test-key");
        event.setTransactionId("tx-1");
        event.setPosition("pos-1");
        event.setSource("test-source");
        event.setMetadata(Map.of("meta", "value"));

        // Then - Lombok @Data should generate getters and setters
        assertThat(event.getKey()).isEqualTo("test-key");
        assertThat(event.getTransactionId()).isEqualTo("tx-1");
        assertThat(event.getPosition()).isEqualTo("pos-1");
        assertThat(event.getSource()).isEqualTo("test-source");
        assertThat(event.getMetadata()).isEqualTo(Map.of("meta", "value"));
    }
}
