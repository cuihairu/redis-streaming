package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
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
public class AckImmediatePolicyIntegrationTest {

    @Test
    void ackImmediateDeletesEntrySingleGroup() throws Exception {
        String topic = "ackimm-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Consumer with ackDeletePolicy=immediate
            MqOptions opts = MqOptions.builder()
                    .ackDeletePolicy("immediate")
                    .defaultConsumerGroup("g1")
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer c = factory.createConsumer("c-imm");
            final int[] handled = {0};
            c.subscribe(topic, "g1", m -> { handled[0]++; return MessageHandleResult.SUCCESS; });
            c.start();

            // Produce one entry after consumer started so group sees it
            String sk = StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> s = client.getStream(sk);
            s.add(StreamAddArgs.entries(Map.of(
                    "payload", "x",
                    "timestamp", Instant.now().toString(),
                    "retryCount", 0,
                    "maxRetries", 3,
                    "topic", topic,
                    "partitionId", 0
            )));

            boolean ok = waitUntil(() -> handled[0] >= 1, 5000);
            assertTrue(ok, "message should be handled");

            // With immediate policy, entry should be deleted
            boolean deleted = waitUntil(() -> s.isExists() ? s.size() == 0 : true, 5000);
            assertTrue(deleted, "stream should be empty after immediate delete");

            c.stop();
            c.close();
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
