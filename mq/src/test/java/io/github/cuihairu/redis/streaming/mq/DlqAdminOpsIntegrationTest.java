package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class DlqAdminOpsIntegrationTest {

    @Test
    void deleteAndClearDlqEntries() {
        String topic = "dlq-admin-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            String dlqKey = StreamKeys.dlq(topic);
            RStream<String,Object> dlq = client.getStream(dlqKey, org.redisson.client.codec.StringCodec.INSTANCE);
            // write two entries
            StreamMessageId id1 = dlq.add(StreamAddArgs.entries(map("originalTopic", topic, "payload", "x", "timestamp", Instant.now().toString(), "partitionId", "0")));
            StreamMessageId id2 = dlq.add(StreamAddArgs.entries(map("originalTopic", topic, "payload", "y", "timestamp", Instant.now().toString(), "partitionId", "0")));

            DeadLetterQueueManager admin = new DeadLetterQueueManager(client);
            long sz = admin.getDeadLetterQueueSize(topic);
            assertTrue(sz >= 2);

            // delete first
            assertTrue(admin.deleteMessage(topic, id1));
            long sz2 = admin.getDeadLetterQueueSize(topic);
            assertTrue(sz2 >= 1);

            // clear remaining
            long cleared = admin.clearDeadLetterQueue(topic);
            assertTrue(cleared >= 0);
            long sz3 = admin.getDeadLetterQueueSize(topic);
            assertEquals(0, sz3);
        } finally { client.shutdown(); }
    }

    private static Map<String,Object> map(String k1, Object v1, String k2, Object v2, String k3, Object v3, String k4, Object v4) {
        Map<String,Object> m = new HashMap<>(); m.put(k1,v1); m.put(k2,v2); m.put(k3,v3); m.put(k4,v4); return m;
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

