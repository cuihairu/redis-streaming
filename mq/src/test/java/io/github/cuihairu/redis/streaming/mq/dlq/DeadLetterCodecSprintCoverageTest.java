package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residual branch coverage for {@link DeadLetterCodec#parseEntry} header normalization.
 */
class DeadLetterCodecSprintCoverageTest {

    @Test
    void mapHeadersDropNullEntries() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        Map<Object, Object> headers = new HashMap<>();
        headers.put(null, "orphan");
        headers.put("k", null);
        headers.put("a", "b");
        data.put("headers", headers);

        DeadLetterEntry entry = DeadLetterCodec.parseEntry("id-1", data);
        assertEquals("b", entry.getHeaders().get("a"));
        assertFalse(entry.getHeaders().containsKey(null));
        assertFalse(entry.getHeaders().containsKey("k"));
    }

    @Test
    void stringHeadersNullJsonAndNullValues() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("headers", "null");

        DeadLetterEntry entry = DeadLetterCodec.parseEntry("id-1", data);
        assertNull(entry.getHeaders().get("k"));
        assertEquals("t", entry.getHeaders().get("originalTopic"));

        Map<String, Object> data2 = new HashMap<>();
        data2.put("originalTopic", "t");
        data2.put("headers", "{\"a\":\"b\",\"z\":null}");

        DeadLetterEntry entry2 = DeadLetterCodec.parseEntry("id-2", data2);
        assertEquals("b", entry2.getHeaders().get("a"));
        assertFalse(entry2.getHeaders().containsKey("z"));
    }

    @Test
    void brokenStringHeadersAreIgnored() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("headers", "{broken");

        DeadLetterEntry entry = DeadLetterCodec.parseEntry("id-1", data);
        assertEquals("t", entry.getHeaders().get("originalTopic"));
        assertFalse(entry.getHeaders().containsKey("a"));
    }
}
