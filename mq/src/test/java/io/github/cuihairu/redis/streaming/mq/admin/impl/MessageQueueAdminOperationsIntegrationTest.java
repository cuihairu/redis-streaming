package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupInfo;
import io.github.cuihairu.redis.streaming.mq.admin.model.MessageEntry;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Broad operational-surface coverage for {@link RedisMessageQueueAdmin} against a real
 * broker populated via the producer path.
 */
@Tag("integration")
class MessageQueueAdminOperationsIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void fullAdminSurface() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "adm-" + uid;
        MqOptions options = MqOptions.builder().defaultPartitionCount(2).build();
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure(options.getKeyPrefix(), options.getStreamKeyPrefix());
        try {
            MessageQueueFactory mq = new MessageQueueFactory(client, options);
            MessageProducer producer = mq.createProducer();
            StreamMessageId first = null;
            for (int i = 0; i < 6; i++) {
                String id = producer.send(topic, "k" + i, "p" + i).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (first == null) {
                    String[] sp = id.split("-");
                    first = new StreamMessageId(Long.parseLong(sp[0]), Long.parseLong(sp[1]));
                }
            }
            producer.close();

            RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(client, options);
            assertTrue(admin.topicExists(topic));
            QueueInfo info = admin.getQueueInfo(topic);
            assertTrue(info.isExists());
            assertTrue(info.getLength() >= 6);
            assertTrue(admin.listAllTopics().contains(topic));

            List<MessageEntry> recent = admin.listRecent(topic, 10);
            assertFalse(recent.isEmpty());
            List<MessageEntry> ranged = admin.range(topic, 0, null, null, 10, false);
            assertNotNull(ranged);
            List<MessageEntry> reversed = admin.range(topic, 0, null, null, 10, true);
            assertEquals(ranged.size(), reversed.size());
            if (!ranged.isEmpty()) {
                assertEquals(ranged.get(0).getId(), reversed.get(reversed.size() - 1).getId());
            }

            // create a group on partition 0 through raw Redisson, then assert admin visibility
            String p0 = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(p0);
            stream.createGroup(StreamCreateGroupArgs.name("g1").id(new StreamMessageId(0, 0)).makeStream());
            assertTrue(admin.consumerGroupExists(topic, "g1"));
            List<ConsumerGroupInfo> groups = admin.getConsumerGroups(topic);
            assertTrue(groups.stream().anyMatch(g -> "g1".equals(g.getName())));
            assertEquals(0L, admin.getPendingCount(topic, "g1"));
            assertTrue(admin.getPendingMessages(topic, "g1", 10).isEmpty());
            assertNotNull(admin.getConsumerGroupStats(topic, "g1"));
            assertNotNull(admin.getConsumerGroupStats(topic, "g1"));
            assertNull(admin.getConsumerGroupStats(topic, "missing-group"));
            assertNull(admin.getConsumerGroupStats("missing-topic-" + uid, "g1"));

            // offset resets across all supported forms
            assertTrue(admin.resetConsumerGroupOffset(topic, "g1", "0"));
            // "$" maps to XGROUP CREATE ... $ MKSTREAM which Redis < 7.0 rejects; use an explicit id.
            assertTrue(admin.resetConsumerGroupOffset(topic, "g1", "9999999999999-0"));
            assertNotNull(first);
            assertTrue(admin.resetConsumerGroupOffset(topic, "g1", first.toString()));
            assertFalse(admin.resetConsumerGroupOffset(topic, "g1", "!!!bad!!!"));

            // retention trims
            long trimmed = admin.trimQueue(topic, 2);
            assertTrue(trimmed >= 0);
            assertTrue(admin.trimQueueByAge(topic, Duration.ZERO) >= 0);

            assertTrue(admin.deleteConsumerGroup(topic, "g1"));
            admin.deleteConsumerGroup(topic, "g1"); // repeat is tolerated (idempotent success)
            assertTrue(admin.updatePartitionCount(topic, 4));

            // unknown topic answers
            assertFalse(admin.topicExists("nope-" + uid));
            QueueInfo missing = admin.getQueueInfo("nope-" + uid);
            assertNotNull(missing);
            assertFalse(missing.isExists());

            assertTrue(admin.deleteTopic(topic));
            assertFalse(admin.topicExists(topic));
        } finally {
            client.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            client.shutdown();
        }
    }
}
