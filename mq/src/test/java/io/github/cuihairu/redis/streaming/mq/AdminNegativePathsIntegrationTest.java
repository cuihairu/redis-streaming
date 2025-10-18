package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminNegativePathsIntegrationTest {

    @Test
    void operationsOnNonexistentTopicAreHandled() {
        String topic = "no-such-topic";
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().build();
            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, opts);

            // topicExists likely false
            assertFalse(admin.topicExists(topic));

            // getQueueInfo exists=false
            QueueInfo info = admin.getQueueInfo(topic);
            assertNotNull(info);
            assertFalse(info.isExists());

            // consumer group checks
            assertFalse(admin.consumerGroupExists(topic, "g"));
            assertTrue(admin.getConsumerGroups(topic).isEmpty());
            assertNull(admin.getConsumerGroupStats(topic, "g"));

            // ops return false/0
            assertEquals(0, admin.trimQueue(topic, 0));
            assertFalse(admin.deleteConsumerGroup(topic, "g"));
            assertFalse(admin.deleteTopic(topic));
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

