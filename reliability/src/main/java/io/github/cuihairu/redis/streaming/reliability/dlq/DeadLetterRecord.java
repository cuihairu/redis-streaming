package io.github.cuihairu.redis.streaming.reliability.dlq;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal DLQ record model used for sending new entries.
 */
public class DeadLetterRecord {
    public String originalTopic;
    public int originalPartition = 0;
    public String originalMessageId;
    public Object payload;
    public Map<String, String> headers;
    public Instant timestamp = Instant.now();
    public int retryCount = 0;
    public int maxRetries = 3;
}

