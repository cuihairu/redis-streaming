package io.github.cuihairu.redis.streaming.mq.broker;

import io.github.cuihairu.redis.streaming.mq.broker.impl.DefaultBroker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ-04 regression: the all-groups-ack deletion gate counted only groups with an active
 * lease as "active". A stopped group (expired lease) was treated as if it did not exist,
 * so another group's ack could delete an entry the stopped group had never read — data
 * loss for that group. The gate must require every REGISTERED consumer group to have
 * acked, regardless of lease liveness; a stopped group will come back and still needs the
 * message.
 */
@Tag("integration")
class BrokerAllGroupsAckStoppedGroupIntegrationTest {

    private RedissonClient client;
    private String topic;
    private String streamKey;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(cfg);
        topic = "mq04-" + UUID.randomUUID().toString().substring(0, 8);
        streamKey = StreamKeys.partitionStream(topic, 0);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern(streamKey);
        client.getKeys().deleteByPattern(StreamKeys.ackSet(topic, 0, "*"));
        client.getKeys().deleteByPattern(StreamKeys.lease(topic, "*", 0));
        client.shutdown();
    }

    @Test
    void stoppedGroupIsNotStarvedOutOfDeletionGate() throws Exception {
        RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
        StreamMessageId id = stream.add(StreamAddArgs.entry("payload", "v1"));
        // repo convention: explicit 0-0 id (StreamMessageId.MIN encodes as "-" which XGROUP rejects)
        StreamMessageId start = new StreamMessageId(0, 0);
        stream.createGroup(StreamCreateGroupArgs.name("gA").id(start).makeStream());
        stream.createGroup(StreamCreateGroupArgs.name("gB").id(start).makeStream());

        // gA is live (lease present); gB is stopped (no lease)
        client.getBucket(StreamKeys.lease(topic, "gA", 0), StringCodec.INSTANCE).set("alive");

        MqOptions options = MqOptions.builder().ackDeletePolicy("all-groups-ack").build();
        DefaultBroker broker = new DefaultBroker(client, options, null, null);

        broker.ack(topic, "gA", 0, id.toString());
        assertEquals(1L, stream.size(),
                "only gA acked; gB (stopped) has not read the entry — it must NOT be deleted (old code deleted it here)");

        broker.ack(topic, "gB", 0, id.toString());
        assertEquals(0L, stream.size(),
                "once every registered group has acked, the entry may be deleted");
        assertEquals(0L, client.getSet(StreamKeys.ackSet(topic, 0, id.toString()), StringCodec.INSTANCE).size(),
                "ack-set is cleaned up after deletion");
    }

    @Test
    void singleRegisteredGroupStillDeletesAfterItsAck() throws Exception {
        RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
        StreamMessageId id = stream.add(StreamAddArgs.entry("payload", "solo"));
        stream.createGroup(StreamCreateGroupArgs.name("only").id(new StreamMessageId(0, 0)).makeStream());
        client.getBucket(StreamKeys.lease(topic, "only", 0), StringCodec.INSTANCE).set("alive");

        MqOptions options = MqOptions.builder().ackDeletePolicy("all-groups-ack").build();
        DefaultBroker broker = new DefaultBroker(client, options, null, null);

        broker.ack(topic, "only", 0, id.toString());
        assertEquals(0L, stream.size(), "a lone registered group acking still deletes the entry");
    }

    @Test
    void immediatePolicyUnchanged() throws Exception {
        RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
        StreamMessageId id = stream.add(StreamAddArgs.entry("payload", "imm"));
        stream.createGroup(StreamCreateGroupArgs.name("imm-g").id(new StreamMessageId(0, 0)).makeStream());

        MqOptions options = MqOptions.builder().ackDeletePolicy("immediate").build();
        DefaultBroker broker = new DefaultBroker(client, options, null, null);
        broker.ack(topic, "imm-g", 0, id.toString());
        assertTrue(stream.size() <= 0, "immediate policy deletes regardless of other groups");
    }
}
