package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.broker.impl.DefaultBroker;
import io.github.cuihairu.redis.streaming.mq.broker.impl.HashBrokerRouter;
import io.github.cuihairu.redis.streaming.mq.broker.impl.RedisBrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisMessageQueueAdmin against real Redis: multi-partition info/exists/groups, pending
 * listing with sorts, offset resets for all id forms, partition expansion, deletes and
 * raw peek/range with boundary ids.
 */
@Tag("integration")
class MessageQueueAdminCoverageIntegrationTest {

    private RedissonClient redis;
    private String uid;
    private String topic;
    private MqOptions options;
    private Broker broker;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redis = Redisson.create(cfg);
        uid = UUID.randomUUID().toString().substring(0, 8);
        topic = "it-mq-ad-" + uid;
        options = MqOptions.builder().defaultPartitionCount(2).build();
        broker = new DefaultBroker(redis, options, new HashBrokerRouter(),
                new RedisBrokerPersistence(redis, options));
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().deleteByPattern("stream:topic:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:retry:*" + topic + "*");
        redis.shutdown();
    }

    private MessageQueueAdmin admin() {
        return new RedisMessageQueueAdmin(redis, options);
    }

    private String produce(String payload) {
        Message m = new Message();
        m.setTopic(topic);
        m.setPayload(payload);
        return broker.produce(m);
    }

    @Test
    void multiPartitionInfoExistsGroupsAndPendingSorts() {
        MessageQueueAdmin admin = admin();
        // pre-create the group on every partition so listPending never hits NOGROUP
        assertTrue(admin.resetConsumerGroupOffset(topic, "pg", "0"));

        produce("m1");
        produce("m2");
        produce("m3");

        assertTrue(admin.getQueueInfo(topic).isExists());
        assertTrue(admin.getQueueInfo(topic).getLength() >= 3);
        assertTrue(admin.topicExists(topic));
        assertTrue(admin.listAllTopics().contains(topic));

        // create pending entries by reading without acking (messages may hash to any partition)
        produce("m4");
        int read = 0;
        for (int pid = 0; pid < 2; pid++) {
            read += broker.readGroup(topic, "pg", "pc-" + pid, pid, 10, 200).size();
        }
        assertTrue(read > 0, "broker readGroup should deliver produced messages");

        assertFalse(admin.getConsumerGroups(topic).isEmpty());
        var pending = admin.getPendingMessages(topic, "pg", 10, PendingSort.ID, true, 0);
        assertFalse(pending.isEmpty());
        // different sort orders all return results (ID sort exercises the comparator lambda)
        assertFalse(admin.getPendingMessages(topic, "pg", 10, PendingSort.DELIVERIES, false, 0).isEmpty());
        assertFalse(admin.getPendingMessages(topic, "pg", 10, PendingSort.IDLE, true, 1).isEmpty());
        assertTrue(admin.getPendingMessages(topic, "pg", 10, PendingSort.IDLE, true, 3_600_000).isEmpty());
        assertTrue(admin.getPendingCount(topic, "pg") > 0);

        // 3-arg overload delegates with IDLE defaults
        assertEquals(admin.getPendingMessages(topic, "pg", 10).size(), pending.size());

        assertNotNull(admin.getConsumerGroupStats(topic, "pg"));
        assertNull(admin.getConsumerGroupStats(topic, "missing-group"));
    }

    @Test
    void resetOffsetsUpdatePartitionsAndDeletes() {
        produce("m1");
        MessageQueueAdmin admin = admin();

        assertTrue(admin.resetConsumerGroupOffset(topic, "g0", "0"));
        assertTrue(admin.resetConsumerGroupOffset(topic, "g1", "$"));
        assertTrue(admin.resetConsumerGroupOffset(topic, "g2", "1234567890-0"));
        assertTrue(admin.resetConsumerGroupOffset(topic, "g3", "77"));
        assertFalse(admin.consumerGroupExists(topic, "nope"));

        assertTrue(admin.updatePartitionCount(topic, 4));
        assertFalse(admin.updatePartitionCount(topic, 2)); // only increases
        assertFalse(admin.updatePartitionCount(topic, 0));
        assertTrue(admin.consumerGroupExists(topic, "g0"));

        assertTrue(admin.deleteConsumerGroup(topic, "g0"));
        assertFalse(admin.consumerGroupExists(topic, "g0"));

        // payload hash cleanup happens through deleteTopic
        PayloadLifecycleManager plm = new PayloadLifecycleManager(redis, options);
        plm.storeLargePayload(topic, 0, "big");
        assertTrue(admin.deleteTopic(topic));
        assertFalse(admin.topicExists(topic));
    }

    @Test
    void listRecentRangeAndTrimByAge() {
        for (int i = 0; i < 5; i++) {
            produce("r" + i);
        }
        MessageQueueAdmin admin = admin();
        var recent = admin.listRecent(topic, 3);
        assertTrue(recent.size() >= 3);

        // forward range with boundary ids
        var fwd = admin.range(topic, 0, "0-0", "+", 10, false);
        assertTrue(fwd.size() + admin.range(topic, 1, "0-0", "+", 10, false).size() >= 5);

        // reverse range with explicit ids and defaults
        assertDoesNotThrow(() -> admin.range(topic, 0, null, null, 5, true));
        assertDoesNotThrow(() -> admin.range(topic, 0, "+", "-", 5, true));
        assertDoesNotThrow(() -> admin.range(topic, 5, "0-0", "$", 5, true)); // pid clamps to pc-1

        assertTrue(admin.trimQueueByAge(topic, Duration.ofMinutes(5)) >= 0);
        assertTrue(admin.trimQueue(topic, 1) >= 0);
    }
}
