package io.github.cuihairu.redis.streaming.source;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceRecordTest {

    @Test
    void testSourceRecordCreation() {
        String source = "test-source";
        String topic = "test-topic";
        String value = "test-value";

        SourceRecord record = new SourceRecord(source, topic, value);

        assertEquals(source, record.getSource());
        assertEquals(topic, record.getTopic());
        assertEquals(value, record.getValue());
        assertNotNull(record.getTimestamp());
        assertNull(record.getKey());
        assertNull(record.getHeaders());
    }

    @Test
    void testSourceRecordWithKey() {
        String source = "test-source";
        String topic = "test-topic";
        String key = "test-key";
        String value = "test-value";

        SourceRecord record = new SourceRecord(source, topic, key, value);

        assertEquals(key, record.getKey());
    }

    @Test
    void testSourceRecordWithHeaders() {
        String source = "test-source";
        String topic = "test-topic";
        String key = "test-key";
        String value = "test-value";
        Map<String, String> headers = Map.of("header1", "value1", "header2", "value2");

        SourceRecord record = new SourceRecord(source, topic, key, value, headers);

        assertEquals(headers, record.getHeaders());
    }

    @Test
    void testSourceRecordFullConstructor() {
        String source = "test-source";
        String topic = "test-topic";
        String key = "test-key";
        String value = "test-value";
        Map<String, String> headers = Map.of("header1", "value1");
        Instant timestamp = Instant.now();
        Map<String, Object> sourceMetadata = Map.of("meta1", "metavalue1");

        SourceRecord record = new SourceRecord(source, topic, key, value, headers, timestamp, sourceMetadata);

        assertEquals(source, record.getSource());
        assertEquals(topic, record.getTopic());
        assertEquals(key, record.getKey());
        assertEquals(value, record.getValue());
        assertEquals(headers, record.getHeaders());
        assertEquals(timestamp, record.getTimestamp());
        assertEquals(sourceMetadata, record.getSourceMetadata());
    }

    @Test
    void testNoArgsConstructor() {
        SourceRecord record = new SourceRecord();
        assertNotNull(record);
        assertNull(record.getSource());
        assertNull(record.getTopic());
        assertNull(record.getKey());
        assertNull(record.getValue());
        assertNull(record.getHeaders());
        assertNull(record.getTimestamp());
        assertNull(record.getSourceMetadata());
    }
}