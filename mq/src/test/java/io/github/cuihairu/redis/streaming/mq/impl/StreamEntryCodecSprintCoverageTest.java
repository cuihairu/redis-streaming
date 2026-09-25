package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link StreamEntryCodec}: original-id header precedence,
 * hash-ref payload resolution combinations and DLQ header normalization.
 */
class StreamEntryCodecSprintCoverageTest {

    // ===== parsePartitionEntry: originalMessageId precedence =====

    @Test
    void parsePartitionEntryBlankOriginalIdDoesNotOverwriteHeader() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalMessageId", "   ");
        data.put("headers", Map.of(MqHeaders.ORIGINAL_MESSAGE_ID, "9-9"));
        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-0", data);
        assertEquals("9-9", m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID));
    }

    // ===== parsePartitionEntry: shouldLoadFromHash combinations =====

    @Test
    void parsePartitionEntryLoadsHashWhenPayloadAbsentDespiteInlineMarker() throws Exception {
        PayloadLifecycleManager plm = mock(PayloadLifecycleManager.class);
        when(plm.loadPayload("ref-1")).thenReturn("loaded");
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "ref-1");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-0", data, plm);
        assertEquals("loaded", m.getPayload());
        assertFalse(m.getHeaders().containsKey(PayloadHeaders.PAYLOAD_HASH_REF), "internal headers must be stripped after load");
    }

    @Test
    void parsePartitionEntryLoadsHashWhenPayloadEmptyString() throws Exception {
        PayloadLifecycleManager plm = mock(PayloadLifecycleManager.class);
        when(plm.loadPayload("ref-2")).thenReturn("loaded");
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "");
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "ref-2");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-0", data, plm);
        assertEquals("loaded", m.getPayload());
    }

    @Test
    void parsePartitionEntryKeepsNonEmptyInlinePayloadWithoutHashLoad() throws Exception {
        PayloadLifecycleManager plm = mock(PayloadLifecycleManager.class);
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "inline-value");
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "ref-3");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-0", data, plm);
        assertEquals("inline-value", m.getPayload());
        assertTrue(m.getHeaders().containsKey(PayloadHeaders.PAYLOAD_HASH_REF), "no load means headers stay untouched");
        verify(plm, never()).loadPayload(anyString());
    }

    @Test
    void parsePartitionEntryWithoutLoaderKeepsRawPayloadAndHeaders() throws Exception {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "ref-4");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parsePartitionEntry("t", "1-0", data, (PayloadLifecycleManager) null);
        assertNull(m.getPayload());
        assertEquals("ref-4", m.getHeaders().get(PayloadHeaders.PAYLOAD_HASH_REF));
    }

    // ===== parseDlqEntry: map-valued headers =====

    @Test
    void parseDlqEntryMergesMapHeaders() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        Map<String, Object> headers = new HashMap<>();
        headers.put("a", "b");
        data.put("headers", headers);

        Message m = StreamEntryCodec.parseDlqEntry("id-1", data);
        assertEquals("b", m.getHeaders().get("a"));
    }

    @Test
    void parseDlqEntrySkipsUnconvertibleMapHeaders() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        Map<String, Object> headers = new HashMap<>();
        headers.put("nested", Map.of("x", "y"));
        data.put("headers", headers);

        Message m = StreamEntryCodec.parseDlqEntry("id-1", data);
        assertFalse(m.getHeaders().containsKey("nested"));
        assertEquals("t", m.getHeaders().get("originalTopic"));
    }

    // ===== parseDlqEntry: shouldLoadFromHash combinations =====

    @Test
    void parseDlqEntryLoadsHashWhenPayloadAbsentWithInlineMarker() throws Exception {
        PayloadLifecycleManager plm = mock(PayloadLifecycleManager.class);
        when(plm.loadPayload("dref-1")).thenReturn("dlq-loaded");
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "dref-1");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parseDlqEntry("id-1", data, plm);
        assertEquals("dlq-loaded", m.getPayload());
        assertFalse(m.getHeaders().containsKey(PayloadHeaders.PAYLOAD_HASH_REF));
    }

    @Test
    void parseDlqEntryDoesNotLoadHashForNonStringPayload() throws Exception {
        PayloadLifecycleManager plm = mock(PayloadLifecycleManager.class);
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("payload", 42);
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "dref-2");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parseDlqEntry("id-1", data, plm);
        assertEquals(42, m.getPayload());
        verify(plm, never()).loadPayload(anyString());
    }

    @Test
    void parseDlqEntryKeepsHeadersWhenHashLoadUnavailable() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("payload", "");
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "dref-3");
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        data.put("headers", headers);

        Message m = StreamEntryCodec.parseDlqEntry("id-1", data, (PayloadLifecycleManager) null);
        assertEquals("", m.getPayload());
        assertEquals("dref-3", m.getHeaders().get(PayloadHeaders.PAYLOAD_HASH_REF), "headers only stripped after an actual load");
    }

    @Test
    void parseDlqEntryEmptyStringPayloadLoadsThroughEmptyCheck() throws Exception {
        PayloadLifecycleManager plm = mock(PayloadLifecycleManager.class);
        when(plm.loadPayload("dref-4")).thenReturn("restored");
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "t");
        data.put("payload", "");
        Map<String, Object> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "dref-4");
        data.put("headers", headers);

        Message m = StreamEntryCodec.parseDlqEntry("id-1", data, plm);
        assertEquals("restored", m.getPayload());
    }

    // ===== shared build helpers remain sane with null payloads =====

    @Test
    void buildEntriesStripNullValues() throws Exception {
        Message m = new Message();
        m.setTopic("t");
        m.setTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        Map<String, Object> partition = StreamEntryCodec.buildPartitionEntry(m, 0);
        assertFalse(partition.containsValue(null));
        assertEquals(0, partition.get("partitionId"));

        Map<String, Object> dlq = StreamEntryCodec.buildDlqEntry(m);
        assertFalse(dlq.containsValue(null));
        assertEquals("t", dlq.get("originalTopic"));
    }
}
