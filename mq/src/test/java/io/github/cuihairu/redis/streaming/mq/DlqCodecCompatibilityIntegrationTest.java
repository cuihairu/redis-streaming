package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.StreamMessageId;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class DlqCodecCompatibilityIntegrationTest {

    @Test
    void dlqListAndReplayWorksForDefaultAndStringCodecEntries() throws Exception {
        String topic = "dlq-compat-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(100).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);

            // Write one DLQ entry with default codec (mixed types)
            String dlqKey = StreamKeys.dlq(topic);
            RStream<String, Object> dlqDefault = client.getStream(dlqKey);
            Map<String, Object> e1 = new HashMap<>();
            e1.put("originalTopic", topic);
            e1.put("payload", "p1");
            e1.put("timestamp", Instant.now().toString());
            e1.put("partitionId", 0);
            dlqDefault.add(StreamAddArgs.entries(e1));

            // Write one DLQ entry with StringCodec (string-only fields)
            RStream<String, Object> dlqStr = client.getStream(dlqKey, org.redisson.client.codec.StringCodec.INSTANCE);
            Map<String, Object> e2 = new HashMap<>();
            e2.put("originalTopic", topic);
            e2.put("payload", "p2");
            e2.put("timestamp", Instant.now().toString());
            e2.put("partitionId", "0");
            dlqStr.add(StreamAddArgs.entries(e2));

            // Admin list can see them via fallback
            DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);
            Map<StreamMessageId, Map<String, Object>> msgs = dlq.getDeadLetterMessages(topic, 10);
            assertNotNull(msgs);
            assertFalse(msgs.isEmpty(), "DLQ should contain entries written with both codecs");

            // Replay first message and verify original stream receives at least one entry
            StreamMessageId first = msgs.keySet().iterator().next();
            assertTrue(dlq.replayMessage(topic, first));

            boolean ok = waitUntil(() -> client.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE).size() > 0, 8000);
            assertTrue(ok, "original stream should receive replayed entry");
        } finally {
            client.shutdown();
        }
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

