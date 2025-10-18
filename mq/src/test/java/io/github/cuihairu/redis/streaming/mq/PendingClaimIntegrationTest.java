package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class PendingClaimIntegrationTest {

    @Test
    void consumerClaimsPendingAndProcesses() throws Exception {
        String topic = "pending-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            // Configure small claimIdleMs/pendingScanInterval for faster test
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .consumerPollTimeoutMs(50)
                    .claimIdleMs(10)
                    .pendingScanIntervalSec(1)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer consumer = factory.createConsumer("c-claim");
            CountDownLatch handled = new CountDownLatch(1);
            consumer.subscribe(topic, "g", m -> { handled.countDown(); return MessageHandleResult.SUCCESS; });
            consumer.start();

            // Create a pending entry for group 'g' by delivering via external consumer without ACK
            String key = StreamKeys.partitionStream(topic, 0);
            RStream<String,Object> s = client.getStream(key, org.redisson.client.codec.StringCodec.INSTANCE);
            try { s.createGroup(StreamCreateGroupArgs.name("g").id(StreamMessageId.MIN).makeStream()); } catch (Exception ignore) {}
            Map<String,Object> data = new HashMap<>();
            data.put("payload", "v"); data.put("timestamp", java.time.Instant.now().toString()); data.put("retryCount", "0"); data.put("maxRetries", "3"); data.put("topic", topic); data.put("partitionId", "0");
            s.add(StreamAddArgs.entries(data));
            // Simulate delivery to another consumer in same group (without ack)
            s.readGroup("g", "ext-consumer", StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofMillis(50)));

            // Our consumer should claim and handle it
            assertTrue(handled.await(5, TimeUnit.SECONDS), "pending claimed and handled");

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

