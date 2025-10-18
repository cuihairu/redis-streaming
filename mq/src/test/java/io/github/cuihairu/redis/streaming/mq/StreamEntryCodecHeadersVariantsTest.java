package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StreamEntryCodecHeadersVariantsTest {

    @Test
    void parseHeadersFromMap() {
        Map<String,Object> data = base();
        Map<String,String> hdr = new HashMap<>(); hdr.put("h", "v");
        data.put("headers", hdr);
        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-1", data);
        assertEquals("v", m.getHeaders().get("h"));
    }

    @Test
    void parseHeadersFromNonJsonStringDoesNotBlowUp() {
        Map<String,Object> data = base();
        data.put("headers", "not-json");
        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-1", data);
        assertNotNull(m);
        // headers may be empty in this case
        assertTrue(m.getHeaders()==null || m.getHeaders().isEmpty());
    }

    private Map<String,Object> base() {
        Map<String,Object> data = new HashMap<>();
        data.put("payload", "v");
        data.put("timestamp", "2025-01-01T00:00:00Z");
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        data.put("topic", "t");
        data.put("partitionId", "0");
        return data;
    }
}

