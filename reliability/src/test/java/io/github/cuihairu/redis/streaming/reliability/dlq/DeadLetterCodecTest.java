package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.Test;
import org.redisson.api.StreamMessageId;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeadLetterCodecTest {

    @Test
    void parseEntry_parsesStringHeadersJson() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-A");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 2);
        data.put("maxRetries", 5);
        data.put("headers", "{\"k1\":\"v1\",\"k2\":\"v2\"}");

        DeadLetterEntry e = DeadLetterCodec.parseEntry(new StreamMessageId(1,2).toString(), data);
        assertEquals("topic-A", e.getOriginalTopic());
        assertEquals(1, e.getPartitionId());
        assertEquals("p", e.getPayload());
        assertEquals(2, e.getRetryCount());
        assertEquals(5, e.getMaxRetries());
        assertEquals("v1", e.getHeaders().get("k1"));
        assertEquals("v2", e.getHeaders().get("k2"));
        // injected convenience headers
        assertEquals("topic-A", e.getHeaders().get("originalTopic"));
        assertEquals("1", e.getHeaders().get("partitionId"));
    }
}

