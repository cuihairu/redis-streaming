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
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetryMoverLuaEdgeIntegrationTest {

    @Test
    void moverDropsItemsWithEmptyTopicAndReplaysWhenPartitionMissing() throws Exception {
        String topic = "luaedge-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .retryMoverIntervalSec(1)
                    .consumerPollTimeoutMs(50)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer consumer = factory.createConsumer("c-luaedge");
            // subscribe to trigger mover loop for this topic
            consumer.subscribe(topic, "g", m -> MessageHandleResult.RETRY);
            consumer.start();

            // Prepare retry bucket keys
            String bucketKey = StreamKeys.retryBucket(topic);
            // 1) Insert an item with empty topic -> mover should ZREM/DEL but not XADD
            {
                String id = UUID.randomUUID().toString();
                String itemKey = StreamKeys.retryItem(topic, id);
                RMap<String,String> item = client.getMap(itemKey, StringCodec.INSTANCE);
                item.put("topic", "");
                item.put("partitionId", "0");
                item.put("payload", "{\"p\":1}");
                item.put("retryCount", "1");
                item.put("maxRetries", "3");
                RScoredSortedSet<String> z = client.getScoredSortedSet(bucketKey, StringCodec.INSTANCE);
                z.add((double) System.currentTimeMillis(), itemKey);
                // Wait for the periodic mover (runs every 1s) to pick this up.
                // The initial run may have occurred before this item was enqueued,
                // so give it a full interval plus a little buffer for scheduling.
                Thread.sleep(1200);
                // item should be removed from ZSET and hash deleted
                assertFalse(client.getMap(itemKey, StringCodec.INSTANCE).isExists());
            }

            // 2) Insert an item without partitionId -> mover should default to 0 and XADD
            {
                String id = UUID.randomUUID().toString();
                String itemKey = StreamKeys.retryItem(topic, id);
                RMap<String,String> item = client.getMap(itemKey, StringCodec.INSTANCE);
                item.put("topic", topic);
                // partitionId missing on purpose
                item.put("payload", "{\"p\":2}");
                item.put("retryCount", "1");
                item.put("maxRetries", "3");
                RScoredSortedSet<String> z = client.getScoredSortedSet(bucketKey, StringCodec.INSTANCE);
                z.add((double) System.currentTimeMillis(), itemKey);
                // wait for mover run
                Thread.sleep(1200);
                // bucket entry should be gone
                assertFalse(client.getMap(itemKey, StringCodec.INSTANCE).isExists());
                // original stream should receive an entry on partition 0
                RStream<String,Object> s = client.getStream(StreamKeys.partitionStream(topic, 0), StringCodec.INSTANCE);
                assertTrue(s.isExists());
                assertTrue(s.size() > 0);
            }

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
