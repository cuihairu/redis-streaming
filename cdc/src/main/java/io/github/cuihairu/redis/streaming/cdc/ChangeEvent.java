package io.github.cuihairu.redis.streaming.cdc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Change event representing a database change
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangeEvent {

    public enum EventType {
        INSERT,
        UPDATE,
        DELETE,
        SCHEMA_CHANGE,
        TRANSACTION_BEGIN,
        TRANSACTION_COMMIT,
        HEARTBEAT
    }

    /**
     * Type of change event
     */
    private EventType eventType;

    /**
     * Database name
     */
    private String database;

    /**
     * Table name
     */
    private String table;

    /**
     * Primary key or unique identifier of the changed row
     */
    private String key;

    /**
     * Data before the change (for UPDATE and DELETE)
     */
    private Map<String, Object> beforeData;

    /**
     * Data after the change (for INSERT and UPDATE)
     */
    private Map<String, Object> afterData;

    /**
     * Timestamp when the change occurred
     */
    private Instant timestamp;

    /**
     * Transaction ID or log sequence number
     */
    private String transactionId;

    /**
     * Position in the change log (e.g., binlog position, LSN)
     */
    private String position;

    /**
     * Additional metadata about the change
     */
    private Map<String, Object> metadata;

    /**
     * Source information (connector name, server details)
     */
    private String source;

    public ChangeEvent(EventType eventType, String database, String table, Map<String, Object> afterData) {
        this.eventType = eventType;
        this.database = database;
        this.table = table;
        this.afterData = afterData;
        this.timestamp = Instant.now();
    }

    public ChangeEvent(EventType eventType, String database, String table, String key,
                      Map<String, Object> beforeData, Map<String, Object> afterData) {
        this(eventType, database, table, afterData);
        this.key = key;
        this.beforeData = beforeData;
    }

    /**
     * Check if this is a data change event (INSERT, UPDATE, DELETE)
     */
    public boolean isDataChange() {
        return eventType == EventType.INSERT ||
               eventType == EventType.UPDATE ||
               eventType == EventType.DELETE;
    }

    /**
     * Check if this is a schema change event
     */
    public boolean isSchemaChange() {
        return eventType == EventType.SCHEMA_CHANGE;
    }

    /**
     * Check if this is a transaction event
     */
    public boolean isTransactionEvent() {
        return eventType == EventType.TRANSACTION_BEGIN ||
               eventType == EventType.TRANSACTION_COMMIT;
    }

    /**
     * Get the fully qualified table name
     */
    public String getFullTableName() {
        return database + "." + table;
    }
}