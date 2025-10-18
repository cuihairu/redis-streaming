package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StreamEntryCodecHeadersJsonTest {

    @Test
    void parseHeadersFromJsonString() {
        Map<String,Object> data = new HashMap<>();
        data.put("payload", "v");
        data.put("timestamp", "2025-01-01T00:00:00Z");
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        data.put("topic", "t");
        data.put("partitionId", "0");
        data.put("headers", "{\"h1\":\"v1\",\"h2\":\"v2\"}");

        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-1", data);
        assertNotNull(m);
        assertEquals("t", m.getTopic());
        assertNotNull(m.getHeaders());
        assertEquals("v1", m.getHeaders().get("h1"));
        assertEquals("v2", m.getHeaders().get("h2"));
    }
}

