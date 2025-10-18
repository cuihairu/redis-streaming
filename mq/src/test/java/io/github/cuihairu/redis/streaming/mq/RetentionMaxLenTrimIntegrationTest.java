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
public class RetentionMaxLenTrimIntegrationTest {

    @Test
    void producerMaxLenTrimKeepsPartitionBounded() throws Exception {
        String topic = "trim-max-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            int maxLen = 5;
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(50)
                    .retentionMaxLenPerPartition(maxLen)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();

            for (int i=0;i<20;i++) producer.send(topic, "k", "v"+i).get();

            RStream<String,Object> s = client.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE);
            long size = s.size();
            assertTrue(size <= maxLen, "stream should be trimmed to <= maxLen");
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

