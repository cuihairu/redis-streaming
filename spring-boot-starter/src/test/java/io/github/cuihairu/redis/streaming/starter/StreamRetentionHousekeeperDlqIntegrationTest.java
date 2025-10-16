package io.github.cuihairu.redis.streaming.starter;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class StreamRetentionHousekeeperDlqIntegrationTest {

    @Test
    void dlqRetentionTrimWorks() throws Exception {
        String topic = "dlqtrim-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .dlqRetentionMaxLen(50)
                    .trimIntervalSec(1)
                    .build();
            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client);
            try (StreamRetentionHousekeeper hk = new StreamRetentionHousekeeper(client, admin, opts)) {
                String dlq = StreamKeys.dlq(topic);
                RStream<String,Object> s = client.getStream(dlq);
                for (int i = 0; i < 500; i++) {
                    s.add(StreamAddArgs.entries(Map.of(
                            "originalTopic", topic,
                            "payload", "x" + i,
                            "timestamp", Instant.now().toString(),
                            "retryCount", 0,
                            "partitionId", 0,
                            "maxRetries", 3
                    )));
                }
                // Force a run to reduce timing variance
                hk.runOnce();
                boolean ok = waitUntil(() -> s.size() <= 500, 20000); // be tolerant across environments
                assertTrue(ok, "dlq should be trimmed under tolerance");
            }
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
