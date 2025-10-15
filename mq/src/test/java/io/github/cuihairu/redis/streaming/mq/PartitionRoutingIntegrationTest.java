package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
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
public class PartitionRoutingIntegrationTest {

    @Test
    void testPartitionRoutingAndAggregation() throws Exception {
        String topic = "prt-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();

        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(3).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageQueueAdmin admin = factory.createAdmin();

            // produce 30 messages with different keys
            for (int i = 0; i < 30; i++) {
                String key = "k" + i;
                producer.send(new Message(topic, key, "v" + i)).get();
            }

            // check at least two partitions have data
            int nonEmpty = 0;
            long total = 0;
            for (int p = 0; p < 3; p++) {
                RStream<String, Object> s = client.getStream(StreamKeys.partitionStream(topic, p));
                long sz = s.size();
                if (sz > 0) nonEmpty++;
                total += sz;
            }
            assertTrue(nonEmpty >= 2, "expected at least two partitions non-empty");

            QueueInfo qi = admin.getQueueInfo(topic);
            assertTrue(qi.isExists());
            assertEquals(total, qi.getLength());

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

