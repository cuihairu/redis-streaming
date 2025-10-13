package io.github.cuihairu.redis.streaming.sink;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SinkRecordTest {

    @Test
    void testSinkRecordCreation() {
        String topic = "test-topic";
        String value = "test-value";

        SinkRecord record = new SinkRecord(topic, value);

        assertEquals(topic, record.getTopic());
        assertEquals(value, record.getValue());
        assertNotNull(record.getTimestamp());
        assertNull(record.getKey());
        assertNull(record.getHeaders());
    }

    @Test
    void testSinkRecordWithKey() {
        String topic = "test-topic";
        String key = "test-key";
        String value = "test-value";

        SinkRecord record = new SinkRecord(topic, key, value);

        assertEquals(key, record.getKey());
    }

    @Test
    void testSinkRecordWithHeaders() {
        String topic = "test-topic";
        String key = "test-key";
        String value = "test-value";
        Map<String, String> headers = Map.of("header1", "value1", "header2", "value2");

        SinkRecord record = new SinkRecord(topic, key, value, headers);

        assertEquals(headers, record.getHeaders());
    }

    @Test
    void testSinkRecordFullConstructor() {
        String topic = "test-topic";
        String key = "test-key";
        String value = "test-value";
        Map<String, String> headers = Map.of("header1", "value1");
        Instant timestamp = Instant.now();
        Integer partition = 1;
        Long offset = 100L;
        Map<String, Object> sinkMetadata = Map.of("meta1", "metavalue1");

        SinkRecord record = new SinkRecord(topic, key, value, headers, timestamp, partition, offset, sinkMetadata);

        assertEquals(topic, record.getTopic());
        assertEquals(key, record.getKey());
        assertEquals(value, record.getValue());
        assertEquals(headers, record.getHeaders());
        assertEquals(timestamp, record.getTimestamp());
        assertEquals(partition, record.getPartition());
        assertEquals(offset, record.getOffset());
        assertEquals(sinkMetadata, record.getSinkMetadata());
    }

    @Test
    void testNoArgsConstructor() {
        SinkRecord record = new SinkRecord();
        assertNotNull(record);
        assertNull(record.getTopic());
        assertNull(record.getKey());
        assertNull(record.getValue());
        assertNull(record.getHeaders());
        assertNull(record.getTimestamp());
        assertNull(record.getPartition());
        assertNull(record.getOffset());
        assertNull(record.getSinkMetadata());
    }
}