package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AdminAggregationIntegrationTest {

    @Test
    void testAggregationAndTrim() throws Exception {
        String topic = "adm-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(2).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageQueueAdmin admin = factory.createAdmin();

            for (int i = 0; i < 40; i++) {
                producer.send(new Message(topic, "k" + i, "v" + i)).get();
            }

            QueueInfo qi = admin.getQueueInfo(topic);
            assertTrue(qi.isExists());
            assertEquals(40, qi.getLength());

            // trim to 10 total approx
            long deleted = admin.trimQueue(topic, 10);
            assertTrue(deleted >= 20); // rough lower bound
            QueueInfo after = admin.getQueueInfo(topic);
            assertTrue(after.getLength() <= 10);

            producer.close();
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

