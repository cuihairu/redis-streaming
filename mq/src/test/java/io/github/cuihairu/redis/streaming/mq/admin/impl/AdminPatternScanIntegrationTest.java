package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.dlq.DlqKeys;
import io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterAdmin;
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
import org.redisson.api.stream.StreamGroup;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ-12: the admin scan paths were switched from full-keyspace getKeys() to patterned
 * scans. These integration cases pin the end-to-end result on real Redis: the DLQ topic
 * listing still finds exactly the DLQ topics, and the pc&lt;=1 deleteConsumerGroup
 * fallback still discovers partitions that exist beyond the enumerated set.
 */
@Tag("integration")
class AdminPatternScanIntegrationTest {

    private RedissonClient client;
    private String uid;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(cfg);
        uid = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern("stream:topic:*scan" + uid + "*");
        client.getKeys().deleteByPattern("streaming:mq:*scan" + uid + "*");
        client.shutdown();
    }

    private void seedStream(String key) {
        client.getStream(key, StringCodec.INSTANCE).add(StreamAddArgs.entry("x", "y"));
    }

    @Test
    void dlqListTopicsFindsDlqTopicsOnly() {
        String topicA = "scan" + uid + "-a";
        String topicB = "scan" + uid + "-b";
        String decoyTopic = "scan" + uid + "-decoy"; // partition stream, NOT a DLQ key
        seedStream(DlqKeys.dlq(topicA));
        seedStream(DlqKeys.dlq(topicB));
        seedStream(StreamKeys.partitionStream(decoyTopic, 0));

        List<String> topics = new RedisDeadLetterAdmin(client, null).listTopics();

        assertTrue(topics.contains(topicA), "DLQ topic A must be listed, got " + topics);
        assertTrue(topics.contains(topicB), "DLQ topic B must be listed, got " + topics);
        assertFalse(topics.contains(decoyTopic), "a partition stream key is not a DLQ topic");
    }

    @Test
    void deleteConsumerGroupFallbackRemovesGroupsOnAllPartitions() {
        String topic = "scan" + uid + "-g";
        String p0 = StreamKeys.partitionStream(topic, 0);
        String p1 = StreamKeys.partitionStream(topic, 1);
        seedStream(p0);
        seedStream(p1);
        createGroup(p0, "g");
        createGroup(p1, "g"); // p:1 is beyond the enumerated pc=1 set — scan must find it

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(client);
        boolean attempted = admin.deleteConsumerGroup(topic, "g");

        assertTrue(attempted, "group removal must have been attempted");
        assertFalse(groupExists(p0, "g"), "group must be gone from partition 0");
        assertFalse(groupExists(p1, "g"), "group must be gone from the scan-discovered partition 1");
    }

    private void createGroup(String streamKey, String group) {
        client.getStream(streamKey, StringCodec.INSTANCE)
                .createGroup(StreamCreateGroupArgs.name(group).id(new StreamMessageId(0, 0)).makeStream());
    }

    private boolean groupExists(String streamKey, String group) {
        RStream<String, Object> stream = client.getStream(streamKey, StringCodec.INSTANCE);
        for (StreamGroup g : stream.listGroups()) {
            if (group.equals(g.getName())) {
                return true;
            }
        }
        return false;
    }
}
