package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetryMoverBadFieldIntegrationTest {

    @Test
    void moverHandlesNonNumericRetryFields() throws Exception {
        String topic = "lua-badnum-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .retryMoverIntervalSec(1)
                    .consumerPollTimeoutMs(50)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer consumer = factory.createConsumer("c-luabad");
            consumer.subscribe(topic, "g", m -> MessageHandleResult.RETRY);
            consumer.start();

            // bad numeric fields in retry item
            String id = UUID.randomUUID().toString();
            String itemKey = StreamKeys.retryItem(topic, id);
            RMap<String,String> item = client.getMap(itemKey, StringCodec.INSTANCE);
            item.put("topic", topic);
            item.put("partitionId", "0");
            item.put("payload", "{\"p\":3}");
            item.put("retryCount", "abc");
            item.put("maxRetries", "xyz");
            RScoredSortedSet<String> z = client.getScoredSortedSet(StreamKeys.retryBucket(topic), StringCodec.INSTANCE);
            z.add((double) System.currentTimeMillis(), itemKey);

            Thread.sleep(1200);
            // item should be cleared and stream receives entry
            assertFalse(client.getMap(itemKey, StringCodec.INSTANCE).isExists());
            RStream<String,Object> s = client.getStream(StreamKeys.partitionStream(topic, 0), StringCodec.INSTANCE);
            assertTrue(s.isExists());
            assertTrue(s.size() > 0);

            consumer.stop(); consumer.close();
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

