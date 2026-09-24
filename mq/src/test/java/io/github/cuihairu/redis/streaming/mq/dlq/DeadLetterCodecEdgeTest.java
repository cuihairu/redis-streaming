package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers DeadLetterCodec.buildPartitionEntryFromDlq and toInt edge branches.
 */
class DeadLetterCodecEdgeTest {

    @Test
    void buildPartitionEntryFromDlqParsesMaxRetriesVariants() {
        Map<String, Object> base = new HashMap<>();
        base.put("payload", "p");
        base.put("key", "k");
        base.put("headers", Map.of("h", "v"));

        Map<String, Object> fromNumber = DeadLetterCodec.buildPartitionEntryFromDlq(base, "t", 1);
        assertEquals(3, fromNumber.get("maxRetries"));
        assertEquals(0, fromNumber.get("retryCount"));
        assertEquals("t", fromNumber.get("topic"));
        assertEquals(1, fromNumber.get("partitionId"));
        assertEquals("k", fromNumber.get("key"));

        base.put("maxRetries", 7);
        assertEquals(7, DeadLetterCodec.buildPartitionEntryFromDlq(base, "t", 0).get("maxRetries"));

        base.put("maxRetries", "5");
        assertEquals(5, DeadLetterCodec.buildPartitionEntryFromDlq(base, "t", 0).get("maxRetries"));

        base.put("maxRetries", "not-a-number");
        assertEquals(3, DeadLetterCodec.buildPartitionEntryFromDlq(base, "t", 0).get("maxRetries"));

        Map<String, Object> bare = new HashMap<>();
        bare.put("payload", null);
        Map<String, Object> out = DeadLetterCodec.buildPartitionEntryFromDlq(bare, "t", 2);
        assertNull(out.get("key"));
        assertNull(out.get("headers"));
    }

    @Test
    void parseEntryToleratesBrokenHeaderJsonAndTimestamp() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("partitionId", 2);
        data.put("payload", "p");
        data.put("timestamp", "not-a-timestamp");
        data.put("retryCount", "x"); // toInt fallback to 0
        data.put("maxRetries", 9);
        data.put("headers", "{broken");

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-1", data);
        assertEquals("t", e.getOriginalTopic());
        assertEquals(2, e.getPartitionId());
        assertEquals(0, e.getRetryCount());
        assertEquals(9, e.getMaxRetries());
        assertEquals("t", e.getHeaders().get("originalTopic"));
        assertEquals("2", e.getHeaders().get("partitionId"));

        DeadLetterEntry empty = DeadLetterCodec.parseEntry("id-2", Map.of());
        assertEquals("", empty.getOriginalTopic());
        assertEquals(0, empty.getPartitionId());
        assertEquals(0, empty.getRetryCount());
        assertEquals(3, empty.getMaxRetries());
    }

    @Test
    void parseEntryWithJsonStringHeaders() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("headers", "{\"a\":\"b\",\"n\":3}");
        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-3", data);
        assertEquals("b", e.getHeaders().get("a"));
        assertEquals("3", e.getHeaders().get("n"));
    }
}
