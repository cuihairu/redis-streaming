package io.github.cuihairu.redis.streaming.reliability.dlq;

import java.time.Instant;
import java.util.Map;

public class DeadLetterEntry {
    private final String id;
    private final String originalTopic;
    private final int partitionId;
    private final Object payload;
    private final Map<String,String> headers;
    private final Instant timestamp;
    private final int retryCount;
    private final int maxRetries;

    public DeadLetterEntry(String id, String originalTopic, int partitionId, Object payload,
                           Map<String, String> headers, Instant timestamp, int retryCount, int maxRetries) {
        this.id = id;
        this.originalTopic = originalTopic;
        this.partitionId = partitionId;
        this.payload = payload;
        this.headers = headers;
        this.timestamp = timestamp;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
    }

    public String getId() { return id; }
    public String getOriginalTopic() { return originalTopic; }
    public int getPartitionId() { return partitionId; }
    public Object getPayload() { return payload; }
    public Map<String, String> getHeaders() { return headers; }
    public Instant getTimestamp() { return timestamp; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
}

