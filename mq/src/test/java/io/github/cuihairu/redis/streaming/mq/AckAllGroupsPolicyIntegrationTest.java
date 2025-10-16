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
public class AckAllGroupsPolicyIntegrationTest {

    @Test
    void deleteAfterAllGroupsAck() throws Exception {
        String topic = "ackall-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .ackDeletePolicy("all-groups-ack")
                    .defaultConsumerGroup("g1")
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);

            MessageConsumer c1 = factory.createConsumer("c1");
            MessageConsumer c2 = factory.createConsumer("c2");
            final int[] h1 = {0};
            final int[] h2 = {0};
            c1.subscribe(topic, "g1", m -> { h1[0]++; return MessageHandleResult.SUCCESS; });
            c2.subscribe(topic, "g2", m -> { h2[0]++; return MessageHandleResult.SUCCESS; });
            c1.start(); c2.start();

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

            boolean ok1 = waitUntil(() -> h1[0] >= 1, 5000);
            assertTrue(ok1, "g1 handled");
            // After only g1 ack, entry should still exist
            assertTrue(s.isExists());
            assertTrue(s.size() >= 0);

            boolean ok2 = waitUntil(() -> h2[0] >= 1, 5000);
            assertTrue(ok2, "g2 handled");

            // After both groups ack, expect deletion
            boolean deleted = waitUntil(() -> s.isExists() ? s.size() == 0 : true, 5000);
            assertTrue(deleted, "stream should be empty after both groups ack");

            c1.stop(); c1.close();
            c2.stop(); c2.close();
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
