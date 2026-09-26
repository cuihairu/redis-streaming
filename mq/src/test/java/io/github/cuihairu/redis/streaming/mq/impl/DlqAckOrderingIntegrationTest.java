package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterRecord;
import io.github.cuihairu.redis.streaming.mq.dlq.DlqKeys;
import io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: (a) DLQ entries must carry the originating partition (used to be hard-coded 0,
 * so replays landed in partition 0); (b) when the DLQ write fails the original message must
 * stay un-acked in the PEL instead of being acknowledged and silently lost.
 */
@Tag("integration")
class DlqAckOrderingIntegrationTest {

    private RedissonClient client;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(cfg);
        topic = "dlq-ack-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern("*" + topic + "*");
        client.shutdown();
    }

    @Test
    void dlqEntryCarriesOriginalPartition() throws Exception {
        MqOptions options = MqOptions.builder()
                .workerThreads(2).schedulerThreads(1)
                .consumerPollTimeoutMs(200)
                .defaultPartitionCount(2)
                .build();
        MessageQueueFactory mq = new MessageQueueFactory(client, options);
        MessageProducer producer = mq.createProducer();
        MessageConsumer consumer = mq.createConsumer("c-" + topic);
        try {
            consumer.subscribe(topic, "g", m -> MessageHandleResult.FAIL);
            consumer.start();

            Message message = new Message();
            message.setTopic(topic);
            message.setKey("k");
            message.setPayload("poison");
            Map<String, String> headers = new HashMap<>();
            headers.put(MqHeaders.FORCE_PARTITION_ID, "1");
            message.setHeaders(headers);
            producer.send(message).get(10, TimeUnit.SECONDS);

            RedisDeadLetterService dlq = new RedisDeadLetterService(client);
            long deadline = System.currentTimeMillis() + 15_000;
            while (dlq.size(topic) == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(1L, dlq.size(topic), "failed message should land in the DLQ");

            Map<StreamMessageId, Map<String, Object>> entries = dlq.range(topic, 10);
            Map<String, Object> entry = entries.values().iterator().next();
            assertEquals(1, ((Number) entry.get("partitionId")).intValue(),
                    "DLQ entry must record the originating partition, not 0");
        } finally {
            consumer.close();
            producer.close();
        }
    }

    @Test
    void dlqWriteFailureLeavesMessageUnAcked() throws Exception {
        MqOptions options = MqOptions.builder()
                .workerThreads(1).schedulerThreads(1)
                .consumerPollTimeoutMs(200)
                .defaultPartitionCount(1)
                .build();
        MessageQueueFactory mq = new MessageQueueFactory(client, options);
        MessageProducer producer = mq.createProducer();
        MessageConsumer consumer = mq.createConsumer("c-" + topic);

        // Occupy the DLQ key as a plain string so the DLQ XADD fails with WRONGTYPE.
        String dlqKey = DlqKeys.dlq(topic);
        client.getBucket(dlqKey, StringCodec.INSTANCE).set("not-a-stream");

        try {
            consumer.subscribe(topic, "g", m -> MessageHandleResult.FAIL);
            consumer.start();
            producer.send(topic, "k", "poison").get(10, TimeUnit.SECONDS);

            Thread.sleep(2500); // give the consumer ample time to process (and fail the DLQ write)

            assertEquals("not-a-stream",
                    client.getBucket(dlqKey, StringCodec.INSTANCE).get(),
                    "DLQ write must have failed");

            var pending = client.getStream(StreamKeys.partitionStream(topic, 0), StringCodec.INSTANCE)
                    .listPending(org.redisson.api.stream.StreamPendingRangeArgs
                    .groupName("g").startId(StreamMessageId.MIN)
                    .endId(StreamMessageId.MAX).count(10));
            assertEquals(1, pending.size(),
                    "message must stay un-acked in the PEL when the DLQ write fails");
        } finally {
            consumer.close();
            producer.close();
        }
    }

    @Test
    void deadLetterRecordCarriesPartitionForReplayRouting() {
        // Guards the codec contract used by replay paths: originalPartition feeds the
        // top-level partitionId field.
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.originalPartition = 1;
        io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterService svc =
                new RedisDeadLetterService(client);
        StreamMessageId id = svc.send(r);
        assertNotNull(id);
        Map<String, Object> entry = svc.range(topic, 10).values().iterator().next();
        assertEquals(1, ((Number) entry.get("partitionId")).intValue());
    }
}
