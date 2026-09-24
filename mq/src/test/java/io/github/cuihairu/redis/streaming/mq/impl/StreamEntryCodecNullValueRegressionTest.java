package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression: entry maps fed to Redisson XADD must never contain null values
 * (Redisson throws {@code NPE: value can't be null}, which used to silently
 * swallow DLQ writes and break hash-stored large payloads).
 */
class StreamEntryCodecNullValueRegressionTest {

    private static void assertNoNullValues(Map<String, Object> entry) {
        entry.forEach((k, v) -> assertNotNull(v, "null value for field " + k));
    }

    @Test
    void buildPartitionEntryWithNullPayloadHasNoNullValues() {
        Message m = new Message();
        m.setTopic("t");
        m.setPayload(null);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(m, 0);

        assertNoNullValues(entry);
        assertFalse(entry.containsKey("payload"));
    }

    @Test
    void buildPartitionEntryHashBranchOmitsPayloadField() {
        PayloadLifecycleManager plm = Mockito.mock(PayloadLifecycleManager.class);
        when(plm.storeLargePayload(anyString(), anyInt(), any())).thenReturn("hash-ref-1");

        Message m = new Message();
        m.setTopic("t");
        m.setPayload("x".repeat(PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1));

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(m, 0, plm);

        assertNoNullValues(entry);
        assertFalse(entry.containsKey("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertEquals("hash-ref-1", headers.get(PayloadHeaders.PAYLOAD_HASH_REF));
    }

    @Test
    void buildDlqEntryWithNullPayloadHasNoNullValues() {
        Message m = new Message();
        m.setTopic("t");
        m.setPayload(null);

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(m);

        assertNoNullValues(entry);
        assertFalse(entry.containsKey("payload"));
    }

    @Test
    void buildDlqEntryHashBranchOmitsPayloadField() {
        PayloadLifecycleManager plm = Mockito.mock(PayloadLifecycleManager.class);
        when(plm.storeLargePayload(anyString(), anyInt(), any())).thenReturn("hash-ref-2");

        Message m = new Message();
        m.setTopic("t");
        m.setPayload("x".repeat(PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1));

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(m, plm);

        assertNoNullValues(entry);
        assertFalse(entry.containsKey("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertEquals("hash-ref-2", headers.get(PayloadHeaders.PAYLOAD_HASH_REF));
    }
}
