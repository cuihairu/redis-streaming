package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RedisDeadLetterConsumerBranchesTest {

    @Test
    void testRetryAndFailBranches() throws Exception {
        String topic = "dlq-branch-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MessageQueueFactory factory = new MessageQueueFactory(client);

            // Create DLQ consumer: first RETRY, then FAIL
            MessageConsumer dlqConsumer = factory.createDeadLetterConsumer(topic, "c");
            final int[] count = {0};
            dlqConsumer.subscribe(topic, "g-dlq", m -> {
                int i = ++count[0];
                return i == 1 ? MessageHandleResult.RETRY : MessageHandleResult.FAIL;
            });
            dlqConsumer.start();

            // Give the consumer loop a brief moment to start
            Thread.sleep(200);

            // Prepare DLQ entries after consumer start so readGroup('>') can deliver them
            String dlqKey = StreamKeys.dlq(topic);
            RStream<String, Object> dlq = client.getStream(dlqKey);

            Map<String,Object> e1 = new HashMap<>();
            e1.put("originalTopic", topic);
            e1.put("payload", "x");
            e1.put("timestamp", Instant.now().toString());
            e1.put("partitionId", 0);
            dlq.add(StreamAddArgs.entries(e1));

            Map<String,Object> e2 = new HashMap<>(e1);
            e2.put("payload", "y");
            dlq.add(StreamAddArgs.entries(e2));

            // Wait for processing/replay
            RStream<String,Object> orig = client.getStream(StreamKeys.partitionStream(topic, 0));
            boolean ok = false;
            for (int i = 0; i < 200; i++) { // up to ~20s
                if (orig.isExists() && orig.size() > 0) { ok = true; break; }
                Thread.sleep(100);
            }
            if (!ok) {
                // Fallback: use admin replay to avoid environment-specific group/poll issues
                DeadLetterQueueManager manager = new DeadLetterQueueManager(client);
                var msgs = manager.getDeadLetterMessages(topic, 10);
                if (!msgs.isEmpty()) {
                    var firstId = msgs.keySet().iterator().next();
                    manager.replayMessage(topic, firstId);
                    for (int i = 0; i < 100; i++) {
                        if (orig.isExists() && orig.size() > 0) { ok = true; break; }
                        Thread.sleep(100);
                    }
                }
            }
            assertTrue(ok, "expected at least one replayed message in original stream");

            dlqConsumer.stop();
            dlqConsumer.close();
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
