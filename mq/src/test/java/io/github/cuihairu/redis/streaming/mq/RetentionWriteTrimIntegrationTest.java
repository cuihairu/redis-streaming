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
public class RetentionWriteTrimIntegrationTest {

    @Test
    void writeTimeTrimKeepsBacklogBounded() throws Exception {
        String topic = "trim-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            int limit = 100; // small limit to verify
            MqOptions opts = MqOptions.builder().retentionMaxLenPerPartition(limit).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer p = factory.createProducer();
            for (int i = 0; i < 1000; i++) {
                p.send(topic, "k" + i, "v" + i).join();
            }
            String sk = StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> s = client.getStream(sk);
            // Eventually the stream should be trimmed close to limit; allow tolerance for async
            boolean ok = waitUntil(() -> s.size() <= limit * 10, 30000);
            assertTrue(ok, "stream should be trimmed under tolerance");
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
