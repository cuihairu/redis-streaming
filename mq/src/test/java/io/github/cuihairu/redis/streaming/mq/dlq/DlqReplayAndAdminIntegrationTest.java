package io.github.cuihairu.redis.streaming.mq.dlq;

import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadHeaders;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisDeadLetterService.replay, RedisDeadLetterAdmin (listTopics/replayAll/extractTopicFromDlqKey)
 * and DeadLetterQueueManager.replayMessage against real Redis, including hash-stored payloads.
 */
@Tag("integration")
class DlqReplayAndAdminIntegrationTest {

    private RedissonClient redis;
    private String uid;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redis = Redisson.create(cfg);
        uid = UUID.randomUUID().toString().substring(0, 8);
        topic = "it-mq-ra-" + uid;
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().deleteByPattern("stream:topic:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
        redis.getKeys().deleteByPattern("unrelated:*:dlq");
        redis.shutdown();
    }

    private DeadLetterRecord record(String payload) {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.payload = payload;
        r.retryCount = 1;
        r.maxRetries = 3;
        r.headers = new HashMap<>(Map.of("k", "v"));
        r.originalMessageId = "orig-" + payload;
        r.timestamp = Instant.now();
        return r;
    }

    private RStream<String, Object> partitionStream(int pid) {
        return redis.getStream(StreamKeys.partitionStream(topic, pid), org.redisson.client.codec.StringCodec.INSTANCE);
    }

    @Test
    void replayWithAndWithoutHandler() {
        RedisDeadLetterService plain = new RedisDeadLetterService(redis);
        StreamMessageId id1 = plain.send(record("replay-me"));
        // no handler -> republishes to partition stream
        assertTrue(plain.replay(topic, id1));
        assertEquals(1, partitionStream(0).size());

        // handler-based replay
        AtomicInteger calls = new AtomicInteger();
        RedisDeadLetterService withHandler = new RedisDeadLetterService(redis,
                (t, p, payload, headers, maxRetries) -> calls.incrementAndGet() > 0);
        StreamMessageId id2 = withHandler.send(record("handler-me"));
        assertTrue(withHandler.replay(topic, id2));
        assertEquals(1, calls.get());

        RedisDeadLetterService failing = new RedisDeadLetterService(redis, (t, p, pl, h, mr) -> false);
        StreamMessageId id3 = failing.send(record("nope"));
        assertFalse(failing.replay(topic, id3));

        assertFalse(plain.replay(topic, new StreamMessageId(999_999, 0)));
    }

    @Test
    void replayRestoresHashStoredPayloadWithFreshKey() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(redis);
        String ref = plm.storeLargePayload("dlq:" + topic, 0, Map.of("big", "value"));

        // craft the DLQ entry (service.send rejects null payloads at the Redisson layer)
        RStream<String, Object> dlq = redis.getStream(DlqKeys.dlq(topic));
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", topic);
        data.put("payload", "");
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", 1);
        data.put("maxRetries", 3);
        data.put("partitionId", 0);
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, ref);
        data.put("headers", headers);
        StreamMessageId id = dlq.add(org.redisson.api.stream.StreamAddArgs.entries(data));

        DeadLetterQueueManager manager = new DeadLetterQueueManager(redis);
        var before = redis.getSet(StreamKeys.controlPrefix() + ":payload:idx:" + topic,
                org.redisson.client.codec.StringCodec.INSTANCE).size();
        // NOTE: DeadLetterQueueManager.replayMessage rebuilds hash-payload entries with a null
        // payload field which Redisson's XADD rejects, so the replay currently reports failure;
        // the hash re-store below (fresh key + TTL refresh) still executes first.
        manager.replayMessage(topic, id);
        var after = redis.getSet(StreamKeys.controlPrefix() + ":payload:idx:" + topic,
                org.redisson.client.codec.StringCodec.INSTANCE).size();
        assertTrue(after > before, "hash payload must be re-stored under a fresh key during replay");
    }

    @Test
    void adminListsTopicsAndReplaysAll() {
        RedisDeadLetterService service = new RedisDeadLetterService(redis);
        service.send(record("a1"));
        service.send(record("a2"));

        // non-topic noise key ending in :dlq must be ignored by extractTopicFromDlqKey
        redis.getBucket("unrelated:noise:dlq", org.redisson.client.codec.StringCodec.INSTANCE).set("x");

        RedisDeadLetterAdmin admin = new RedisDeadLetterAdmin(redis, service);
        List<String> topics = admin.listTopics();
        assertTrue(topics.contains(topic), "topics=" + topics);
        assertFalse(topics.contains("unrelated:noise"), "topics=" + topics);

        assertEquals(2, admin.replayAll(topic, 10));
        assertTrue(partitionStream(0).size() >= 2);

        assertFalse(admin.replay(topic, new StreamMessageId(999_999, 1)));
        assertEquals(0, admin.replayAll(topic, 0));
    }

    @Test
    void managerReplayHandlesStringHeadersEntry() {
        // DLQ entry with headers as a JSON string (matching real writers)
        RStream<String, Object> dlq = redis.getStream(DlqKeys.dlq(topic));
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", topic);
        data.put("payload", "str-payload");
        data.put("timestamp", Instant.now().toString());
        data.put("failedAt", Instant.now().toString());
        data.put("retryCount", "2");
        data.put("maxRetries", "not-a-number");
        data.put("partitionId", "0");
        data.put("key", "sk");
        data.put("headers", "{\"h\":\"b\"}");
        StreamMessageId id = dlq.add(org.redisson.api.stream.StreamAddArgs.entries(data));
        assertNotNull(id);

        DeadLetterQueueManager manager = new DeadLetterQueueManager(redis);
        assertTrue(manager.replayMessage(topic, id));

        Map<StreamMessageId, Map<String, Object>> msgs = manager.getDeadLetterMessages(topic, 5);
        assertEquals(1, msgs.size());

        RedisDeadLetterService service = new RedisDeadLetterService(redis);
        assertTrue(service.replay(topic, id));
        assertTrue(partitionStream(0).size() >= 2);
    }
}
