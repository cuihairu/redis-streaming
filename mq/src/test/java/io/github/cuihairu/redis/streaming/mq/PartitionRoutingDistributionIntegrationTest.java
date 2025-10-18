package io.github.cuihairu.redis.streaming.mq;

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
public class PartitionRoutingDistributionIntegrationTest {

    @Test
    void messagesDistributedAcrossPartitions() throws Exception {
        String topic = "route-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(3).consumerPollTimeoutMs(50).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();

            int total = 60;
            for (int i = 0; i < total; i++) {
                String key = "k" + (i % 10);
                producer.send(topic, key, "v" + i).get();
            }

            // Sum across partitions and ensure more than one partition has entries
            int pc = 3;
            long sum = 0;
            int nonEmpty = 0;
            for (int i = 0; i < pc; i++) {
                RStream<String, Object> s = client.getStream(StreamKeys.partitionStream(topic, i), org.redisson.client.codec.StringCodec.INSTANCE);
                long sz = s.size();
                sum += sz;
                if (sz > 0) nonEmpty++;
            }
            assertEquals(total, sum, "sum across partitions should equal total produced");
            assertTrue(nonEmpty >= 2, "expect distribution across at least two partitions");
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

