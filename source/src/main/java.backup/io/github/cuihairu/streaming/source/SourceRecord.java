package io.github.cuihairu.redis.streaming.source;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Source record representing data from a source connector
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceRecord {

    /**
     * Source connector name/identifier
     */
    private String source;

    /**
     * Topic to send the data to
     */
    private String topic;

    /**
     * Record key for partitioning (optional)
     */
    private String key;

    /**
     * Record payload/value
     */
    private Object value;

    /**
     * Record headers/metadata
     */
    private Map<String, String> headers;

    /**
     * Timestamp when the record was created
     */
    private Instant timestamp;

    /**
     * Source-specific metadata
     */
    private Map<String, Object> sourceMetadata;

    public SourceRecord(String source, String topic, Object value) {
        this.source = source;
        this.topic = topic;
        this.value = value;
        this.timestamp = Instant.now();
    }

    public SourceRecord(String source, String topic, String key, Object value) {
        this(source, topic, value);
        this.key = key;
    }

    public SourceRecord(String source, String topic, String key, Object value, Map<String, String> headers) {
        this(source, topic, key, value);
        this.headers = headers;
    }
}