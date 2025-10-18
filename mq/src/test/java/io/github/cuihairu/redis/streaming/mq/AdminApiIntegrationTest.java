package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupInfo;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupStats;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminApiIntegrationTest {

    @Test
    void adminOpsWorkEndToEnd() throws Exception {
        String topic = "admin-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumer = factory.createConsumer("c-admin");
            consumer.subscribe(topic, "g1", m -> MessageHandleResult.SUCCESS);
            consumer.start();
            producer.send(topic, "k", "v").get();

            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, opts);

            // listAllTopics includes our topic (best effort)
            List<String> topics = admin.listAllTopics();
            assertTrue(topics.contains(topic));

            // Queue info exists and has >=0 length
            QueueInfo info = admin.getQueueInfo(topic);
            assertNotNull(info);
            assertTrue(info.isExists());

            // Consumer groups list contains g1
            List<ConsumerGroupInfo> groups = admin.getConsumerGroups(topic);
            assertTrue(groups.stream().anyMatch(g -> g.getName().equals("g1")));

            // Consumer group stats non-null
            ConsumerGroupStats stats = admin.getConsumerGroupStats(topic, "g1");
            assertNotNull(stats);

            // Reset offset to 0
            assertTrue(admin.resetConsumerGroupOffset(topic, "g1", "0"));

            // Trim by maxLen
            admin.trimQueue(topic, 0);
            // Trim by age
            admin.trimQueueByAge(topic, Duration.ofMillis(1));

            // Update partition count
            assertTrue(admin.updatePartitionCount(topic, 2));

            // Delete consumer group
            assertTrue(admin.deleteConsumerGroup(topic, "g1"));
            // Delete topic
            assertTrue(admin.deleteTopic(topic));

            consumer.stop(); consumer.close(); producer.close();
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

