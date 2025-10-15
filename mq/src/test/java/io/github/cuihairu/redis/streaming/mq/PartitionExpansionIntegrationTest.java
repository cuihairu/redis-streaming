package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class PartitionExpansionIntegrationTest {

    @Test
    void testIncreasePartitionCount() throws Exception {
        String topic = "pex-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageQueueAdmin admin = factory.createAdmin();

            // write few messages with single partition
            for (int i = 0; i < 5; i++) producer.send(new Message(topic, "k0", "v")).get();

            // expand to 3
            boolean updated = admin.updatePartitionCount(topic, 3);
            assertTrue(updated);

            // write after expansion with diverse keys; expect new partitions to get data
            for (int i = 0; i < 30; i++) producer.send(new Message(topic, "k" + i, "v")).get();

            int nonEmpty = 0;
            for (int p = 0; p < 3; p++) {
                RStream<String, Object> s = client.getStream(StreamKeys.partitionStream(topic, p));
                if (s.isExists() && s.size() > 0) nonEmpty++;
            }
            assertTrue(nonEmpty >= 2);

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

