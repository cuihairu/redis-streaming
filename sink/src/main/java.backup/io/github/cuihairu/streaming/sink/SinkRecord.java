package io.github.cuihairu.redis.streaming.sink;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Sink record representing data to be written to a sink connector
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SinkRecord {

    /**
     * Source topic this record came from
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
     * Partition information (optional)
     */
    private Integer partition;

    /**
     * Offset information (optional)
     */
    private Long offset;

    /**
     * Sink-specific metadata
     */
    private Map<String, Object> sinkMetadata;

    public SinkRecord(String topic, Object value) {
        this.topic = topic;
        this.value = value;
        this.timestamp = Instant.now();
    }

    public SinkRecord(String topic, String key, Object value) {
        this(topic, value);
        this.key = key;
    }

    public SinkRecord(String topic, String key, Object value, Map<String, String> headers) {
        this(topic, key, value);
        this.headers = headers;
    }
}