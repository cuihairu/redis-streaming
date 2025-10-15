package io.github.cuihairu.redis.streaming.mq.retry;

import java.util.Map;

/**
 * Serializable envelope stored in retry bucket (ZSET). JSON serialized via Jackson.
 * Payload is stored as-is (may become JSON string) and re-enqueued into the stream on due.
 */
public class RetryEnvelope {
    private String topic;
    private Integer partitionId;
    private Object payload;
    private String key;
    private Map<String, String> headers;
    private int retryCount;
    private int maxRetries;
    private String originalMessageId;

    public RetryEnvelope() {}

    public RetryEnvelope(String topic, Integer partitionId, Object payload, String key,
                         Map<String, String> headers, int retryCount, int maxRetries, String originalMessageId) {
        this.topic = topic;
        this.partitionId = partitionId;
        this.payload = payload;
        this.key = key;
        this.headers = headers;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
        this.originalMessageId = originalMessageId;
    }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public Integer getPartitionId() { return partitionId; }
    public void setPartitionId(Integer partitionId) { this.partitionId = partitionId; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getOriginalMessageId() { return originalMessageId; }
    public void setOriginalMessageId(String originalMessageId) { this.originalMessageId = originalMessageId; }
}

