package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Message representation for the streaming system
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    /**
     * Unique message ID
     */
    private String id;

    /**
     * Message topic/stream name
     */
    private String topic;

    /**
     * Message payload
     */
    private Object payload;

    /**
     * Message headers/metadata
     */
    private Map<String, String> headers;

    /**
     * Message timestamp
     */
    private Instant timestamp;

    /**
     * Message key for partitioning (optional)
     */
    private String key;

    /**
     * Message publisher
     */
    private String publisher;

    /**
     * Retry count for failed messages
     */
    private int retryCount;

    /**
     * Maximum retry attempts
     */
    private int maxRetries;

    public Message(String topic, Object payload) {
        this.topic = topic;
        this.payload = payload;
        this.timestamp = Instant.now();
        this.retryCount = 0;
        this.maxRetries = 3;
        this.publisher = SystemUtils.getLocalHostname();
    }

    public Message(String topic, Object payload, Map<String, String> headers, String publisher) {
        this(topic, payload);
        this.headers = headers;
        this.publisher = publisher;
    }

    public Message(String topic, Object payload, Map<String, String> headers) {
        this(topic, payload);
        this.headers = headers;
    }

    public Message(String topic, String key, Object payload, String publisher) {
        this(topic, payload);
        this.key = key;
        this.publisher = publisher;
    }

    public Message(String topic, String key, Object payload) {
        this(topic, payload);
        this.key = key;
    }

    /**
     * Check if message has exceeded max retry attempts
     */
    public boolean hasExceededMaxRetries() {
        return retryCount >= maxRetries;
    }

    /**
     * Increment retry count
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }
}
