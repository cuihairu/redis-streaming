package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminPartitionUpdateEdgeIntegrationTest {

    @Test
    void updatePartitionCountOnlyIncreases() {
        String topic = "updpart-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            // Initialize topic by sending one message
            factory.createProducer().send(topic, "k", "v");

            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, opts);
            // Same count should be false
            assertFalse(admin.updatePartitionCount(topic, 1));
            // Lower count should be false
            assertFalse(admin.updatePartitionCount(topic, 0));
            // Increase should be true
            assertTrue(admin.updatePartitionCount(topic, 2));
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

