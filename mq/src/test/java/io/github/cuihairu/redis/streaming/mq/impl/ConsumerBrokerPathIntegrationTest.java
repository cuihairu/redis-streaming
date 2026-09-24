package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.impl.DefaultBroker;
import io.github.cuihairu.redis.streaming.mq.broker.impl.RedisBrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.broker.impl.HashBrokerRouter;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Broker-backed consumption (RedisMessageConsumer with Broker), DefaultBroker.readGroup/ack
 * policies (none/immediate/all-groups-ack with real leases) and RedisBrokerPersistence.append
 * retention paths against real Redis.
 */
@Tag("integration")
class ConsumerBrokerPathIntegrationTest {

    private RedissonClient redis;
    private String uid;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redis = Redisson.create(cfg);
        uid = UUID.randomUUID().toString().substring(0, 8);
        topic = "it-mq-br-" + uid;
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().deleteByPattern("stream:topic:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:retry:*" + topic + "*");
        redis.shutdown();
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private RStream<String, Object> stream(int pid) {
        return redis.getStream(StreamKeys.partitionStream(topic, pid), org.redisson.client.codec.StringCodec.INSTANCE);
    }

    @Test
    void brokerConsumerProcessesAndMissingPayloadPrecheckGoesToDlq() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(30)
                .build();
        MessageQueueFactory mq = new MessageQueueFactory(redis, options);
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        RedisMessageConsumer consumer =
                (RedisMessageConsumer) mq.createConsumer("br-c-" + uid);
        try {
            consumer.subscribe(topic, "g", m -> {
                handled.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();

            // produced through the broker (BrokerBackedProducer)
            mq.createProducer().send(topic, "k1", "hello-broker").get(5, TimeUnit.SECONDS);

            // crafted entry whose hash payload ref is missing -> broker precheck routes to DLQ
            Map<String, Object> data = new HashMap<>();
            data.put("payload", "");
            data.put("timestamp", Instant.now().toString());
            data.put("retryCount", "0");
            data.put("maxRetries", "3");
            data.put("topic", topic);
            data.put("partitionId", "0");
            data.put("headers", "{\"" + PayloadHeaders.PAYLOAD_STORAGE_TYPE + "\":\"hash\",\""
                    + PayloadHeaders.PAYLOAD_HASH_REF + "\":\"streaming:mq:payload:" + topic + ":p:0:gone\"}");
            stream(0).add(org.redisson.api.stream.StreamAddArgs.entries(data));

            assertTrue(waitUntil(() -> handled.stream().anyMatch(p -> p.contains("hello-broker")), 15_000),
                    "broker-produced message should be handled, handled=" + handled);
            // the missing-payload entry must be routed through handleMissingPayload and
            // acked (not left as poison pending); its handler must never run
            assertTrue(waitUntil(() -> stream(0).listPending("g", StreamMessageId.MIN,
                    StreamMessageId.MAX, 100).isEmpty(), 15_000),
                    "missing payload entry should be acked out of the PEL");
            assertFalse(handled.stream().anyMatch(p -> p.isEmpty()));
        } finally {
            consumer.stop();
            consumer.close();
        }
    }

    @Test
    void defaultBrokerAckPoliciesAndReadGroupEdge() throws Exception {
        // append with retention bound (Lua MAXLEN path) and without (fallback path)
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .ackDeletePolicy("none")
                .retentionMaxLenPerPartition(3)
                .build();
        Broker broker = new DefaultBroker(redis, options, new HashBrokerRouter(),
                new RedisBrokerPersistence(redis, options));
        Message m = new Message();
        m.setTopic(topic);
        m.setPayload("p1");
        assertNotNull(broker.produce(m));
        assertNotNull(broker.produce(m));
        assertNotNull(broker.produce(m));
        assertNotNull(broker.produce(m)); // exceeds maxLen -> Lua MAXLEN trims
        assertTrue(stream(0).size() <= 3);

        // readGroup with negative timeout normalizes to 0
        var records = broker.readGroup(topic, "bg", "bc", 0, 10, -5);
        assertFalse(records.isEmpty());
        String id = records.get(0).getId();

        // policy none: ack without delete
        broker.ack(topic, "bg", 0, id);
        assertTrue(stream(0).size() > 0);

        // policy immediate: ack deletes the entry
        Broker immediate = new DefaultBroker(redis, MqOptions.builder().ackDeletePolicy("immediate").build(),
                new HashBrokerRouter(), new RedisBrokerPersistence(redis, MqOptions.builder().build()));
        long sizeBefore = stream(0).size();
        immediate.ack(topic, "bg", 0, id);
        assertEquals(sizeBefore - 1, stream(0).size());

        // policy all-groups-ack: entry is deleted once every group with a live lease acks
        Message m2 = new Message();
        m2.setTopic(topic);
        m2.setPayload("p2");
        String id2 = broker.produce(m2);
        MqOptions allOpts = MqOptions.builder().ackDeletePolicy("all-groups-ack").acksetTtlSec(60).build();
        Broker all = new DefaultBroker(redis, allOpts, new HashBrokerRouter(),
                new RedisBrokerPersistence(redis, allOpts));
        redis.getBucket(StreamKeys.lease(topic, "bg", 0), org.redisson.client.codec.StringCodec.INSTANCE)
                .set("bc", java.time.Duration.ofSeconds(30));
        long size2 = stream(0).size();
        all.ack(topic, "bg", 0, id2);
        // single group with live lease -> delete should trigger
        assertTrue(waitUntil(() -> {
            try {
                return stream(0).size() < size2;
            } catch (Exception e) {
                return false;
            }
        }, 5_000) || stream(0).size() < size2, "all-groups-ack should delete after the only live group acks");

        // broker ack with broken stream id: parseStreamId falls back to MIN and the
        // invalid XACK surfaces as an error instead of corrupting state
        assertThrows(Exception.class, () -> all.ack(topic, "bg", 0, "not-a-stream-id"));
    }

    @Test
    void redisBrokerPersistenceAppendWithoutRetentionBound() {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .retentionMaxLenPerPartition(0)
                .build();
        RedisBrokerPersistence persistence = new RedisBrokerPersistence(redis, options);
        Message m = new Message();
        m.setTopic(topic);
        m.setPayload("plain");
        String id = persistence.append(topic, 0, m);
        assertNotNull(id);

        Message complex = new Message();
        complex.setTopic(topic);
        complex.setPayload(Map.of("k", "v"));
        complex.setHeaders(Map.of("h1", "v1"));
        complex.setKey("key");
        complex.setTimestamp(Instant.now());
        assertNotNull(persistence.append(topic, 0, complex));

        assertNull(persistence.append(null, 0, m));
        assertNull(persistence.append(topic, 0, null));
    }
}
