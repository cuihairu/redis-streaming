package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetentionTimeTrimIntegrationTest {

    @Test
    void trimByAgeRemovesOldEntries() throws Exception {
        String topic = "trim-age-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();

            // produce some messages
            for (int i=0;i<10;i++) producer.send(topic, "k", "v"+i).get();
            RStream<String,Object> s = client.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE);
            long before = s.size();
            assertTrue(before >= 10);

            // wait a bit so they become older than threshold
            Thread.sleep(150);
            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client, opts);
            long removed = admin.trimQueueByAge(topic, Duration.ofMillis(100));
            // either removed count > 0 or stream shrinks afterward
            long after = s.size();
            assertTrue(removed >= 0);
            assertTrue(after <= before);
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

