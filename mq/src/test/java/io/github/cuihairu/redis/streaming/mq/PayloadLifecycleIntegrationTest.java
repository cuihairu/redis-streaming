package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class PayloadLifecycleIntegrationTest {

    @Test
    void largePayloadStoredThenCleanedOnAck() throws Exception {
        String topic = "plm-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(100)
                    .build();
            // Configure StreamKeys prefixes once
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumer = factory.createConsumer("c-plm");

            CountDownLatch handled = new CountDownLatch(1);
            consumer.subscribe(topic, "g", m -> { handled.countDown(); return MessageHandleResult.SUCCESS; });
            consumer.start();

            // Build a large string payload (>64KB) to trigger hash storage
            byte[] big = new byte[80_000];
            java.util.Arrays.fill(big, (byte) 'A');
            String payload = new String(big, StandardCharsets.UTF_8);

            producer.send(topic, payload).get(5, TimeUnit.SECONDS);
            assertTrue(handled.await(5, TimeUnit.SECONDS));

            // Verify indices are empty for this topic (payload hash removed on ACK)
            String idxKey = StreamKeys.controlPrefix() + ":payload:idx:" + topic;
            String tsKey = StreamKeys.controlPrefix() + ":payload:ts";
            RSet<String> idx = client.getSet(idxKey, org.redisson.client.codec.StringCodec.INSTANCE);
            boolean ok = waitUntil(() -> idx.isExists() ? idx.size() == 0 : true, 5000);
            assertTrue(ok, "payload index set should be empty for topic");

            // If any key was left by race, ensure corresponding bucket doesn't exist
            for (String k : idx.readAll()) {
                RBucket<String> b = client.getBucket(k, org.redisson.client.codec.StringCodec.INSTANCE);
                assertFalse(b.isExists(), "payload bucket should be deleted: " + k);
            }

            // ZSET time index should not contain our topic key entries anymore
            RScoredSortedSet<String> z = client.getScoredSortedSet(tsKey, org.redisson.client.codec.StringCodec.INSTANCE);
            for (String v : z.readAll()) {
                assertFalse(v.contains(":payload:" + topic + ":"), "time index should not contain topic payload key");
            }

            consumer.stop(); consumer.close(); producer.close();
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

