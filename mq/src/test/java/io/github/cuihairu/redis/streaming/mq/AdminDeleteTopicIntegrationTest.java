package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminDeleteTopicIntegrationTest {

    @Test
    void testDeleteTopicAcrossPartitionsAndDlq() throws Exception {
        String topic = "adm-del-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(3).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageQueueAdmin admin = factory.createAdmin();

            // Produce a few messages so streams exist
            for (int i = 0; i < 9; i++) {
                producer.send(new Message(topic, "k" + i, "v" + i)).get();
            }

            // Create a DLQ entry manually
            String dlqKey = StreamKeys.dlq(topic);
            client.getStream(dlqKey)
                    .add(org.redisson.api.stream.StreamAddArgs.entries(java.util.Map.of(
                            "payload", "dead",
                            "timestamp", java.time.Instant.now().toString(),
                            "partitionId", 0
                    )));

            // Delete the topic
            boolean ok = admin.deleteTopic(topic);
            assertTrue(ok, "deleteTopic should return true when it removed some keys");

            // Verify partition streams and DLQ are removed
            for (int p = 0; p < 3; p++) {
                RStream<String, Object> s = client.getStream(StreamKeys.partitionStream(topic, p));
                assertFalse(s.isExists(), "partition stream should be removed");
            }
            assertFalse(client.getStream(dlqKey).isExists(), "dlq stream should be removed");

            // Verify meta keys removed (best-effort)
            RKeys keys = client.getKeys();
            assertEquals(0, keys.countExists(StreamKeys.topicMeta(topic)), "meta key should be removed");
            assertEquals(0, keys.countExists(StreamKeys.topicPartitionsSet(topic)), "partitions set should be removed");

            producer.close();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void testDeleteConsumerGroupAcrossPartitions() {
        String topic = "adm-cg-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(2).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageQueueAdmin admin = factory.createAdmin();

            // Ensure groups exist on both partitions
            for (int p = 0; p < 2; p++) {
                String key = StreamKeys.partitionStream(topic, p);
                client.getStream(key).createGroup(StreamCreateGroupArgs.name("g").makeStream());
            }

            boolean ok = admin.deleteConsumerGroup(topic, "g");
            assertTrue(ok);

            for (int p = 0; p < 2; p++) {
                var groups = client.getStream(StreamKeys.partitionStream(topic, p)).listGroups();
                assertTrue(groups.stream().noneMatch(g -> g.getName().equals("g")));
            }
        } finally {
            client.shutdown();
        }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

