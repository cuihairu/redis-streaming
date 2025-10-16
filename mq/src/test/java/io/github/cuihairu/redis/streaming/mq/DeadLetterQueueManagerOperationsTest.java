package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.config.Config;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class DeadLetterQueueManagerOperationsTest {

    @Test
    @SuppressWarnings("deprecation")
    void testDeleteAndClearAndInvalidId() {
        String topic = "dlqop-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);
            String dlqKey = StreamKeys.dlq(topic);
            var s = client.getStream(dlqKey);

            // add two entries
            StreamMessageId id1 = s.add(org.redisson.api.stream.StreamAddArgs.entries(Map.of(
                    "payload", "a", "timestamp", java.time.Instant.now().toString(), "partitionId", 0)));
            StreamMessageId id2 = s.add(org.redisson.api.stream.StreamAddArgs.entries(Map.of(
                    "payload", "b", "timestamp", java.time.Instant.now().toString(), "partitionId", 0)));

            assertTrue(dlq.getDeadLetterQueueSize(topic) >= 2);

            // delete one
            assertTrue(dlq.deleteMessage(topic, id1));
            // delete invalid
            assertFalse(dlq.deleteMessage(topic, new StreamMessageId(0)));

            // clear all
            long cleared = dlq.clearDeadLetterQueue(topic);
            assertTrue(cleared >= 1);
            assertEquals(0, dlq.getDeadLetterQueueSize(topic));
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
