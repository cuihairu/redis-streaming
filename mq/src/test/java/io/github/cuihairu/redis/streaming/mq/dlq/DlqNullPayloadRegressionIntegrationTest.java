package io.github.cuihairu.redis.streaming.mq.dlq;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadHeaders;
import io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression against the Redisson "value can't be null" XADD failures: null-payload
 * DLQ writes used to be swallowed silently and hash-stored large payloads could
 * never be written at all.
 */
@Tag("integration")
class DlqNullPayloadRegressionIntegrationTest {

    private RedissonClient client;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(cfg);
        topic = "dlq-null-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern("*" + topic + "*");
        client.shutdown();
    }

    @Test
    void sendWithNullPayloadLandsInDlq() {
        RedisDeadLetterService dlq = new RedisDeadLetterService(client);
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = topic;
        record.payload = null;
        record.headers = new HashMap<>();

        StreamMessageId id = dlq.send(record);

        assertNotNull(id, "null-payload record must still reach the DLQ");
        assertEquals(1L, dlq.size(topic));
    }

    @Test
    void replayOfMissingPayloadEntryReenqueuesWithMarker() {
        RedisDeadLetterService dlq = new RedisDeadLetterService(client);
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = topic;
        record.payload = null;
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "missing:" + topic);
        record.headers = headers;

        StreamMessageId id = dlq.send(record);
        assertNotNull(id);

        boolean replayed = dlq.replay(topic, id);

        assertTrue(replayed, "replay of a missing-payload entry must re-enqueue with markers");
        // RedisDeadLetterService.re-enqueues with the default codec (not StringCodec)
        org.redisson.api.RStream<String, Object> source =
                client.getStream(StreamKeys.partitionStream(topic, 0));
        var entries = source.range(org.redisson.api.stream.StreamMessageId.MIN,
                org.redisson.api.stream.StreamMessageId.MAX);
        assertEquals(1, entries.size(), "source stream should hold the re-enqueued entry");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = entries.values().iterator().next();
        assertNull(data.get("payload"), "payload field must be absent for missing payloads");
        String hdr = String.valueOf(data.get("headers"));
        assertTrue(hdr.contains("x-payload-missing"), "missing marker expected in headers: " + hdr);
    }

    @Test
    void largePayloadHashRoundTrips() throws Exception {
        MessageQueueFactory mq = new MessageQueueFactory(client, MqOptions.builder().build());
        MessageProducer producer = mq.createProducer();
        String big = "x".repeat(PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1);

        assertDoesNotThrow(() -> producer.send(topic, "k", big).get(10, TimeUnit.SECONDS),
                "hash-stored large payload must be XADD-able (was: NPE value can't be null)");

        org.redisson.api.RStream<String, Object> stream =
                client.getStream(StreamKeys.partitionStream(topic, 0), StringCodec.INSTANCE);
        var entries = stream.range(org.redisson.api.stream.StreamMessageId.MIN,
                org.redisson.api.stream.StreamMessageId.MAX);
        assertEquals(1, entries.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = entries.values().iterator().next();
        assertNull(data.get("payload"), "payload must live in the hash, not the stream entry");

        Message m = StreamEntryCodec.parsePartitionEntry(topic, "id", data, client, null);
        assertEquals(big, m.getPayload(), "payload must be restored from the hash");
    }
}
