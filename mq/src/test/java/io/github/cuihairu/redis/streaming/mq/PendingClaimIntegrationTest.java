package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class PendingClaimIntegrationTest {

    @Test
    void testClaimPendingFromStuckConsumer() throws Exception {
        String topic = "claim-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g";
        String stuckConsumer = "stuck";
        RedissonClient client = createClient();
        try {
            // prepare stream & group, add 1 message
            String sKey = StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(sKey);
            stream.createGroup(StreamCreateGroupArgs.name(group).makeStream());
            java.util.Map<String, Object> d = new java.util.HashMap<>();
            d.put("payload", "v");
            d.put("timestamp", java.time.Instant.now().toString());
            stream.add(org.redisson.api.stream.StreamAddArgs.entries(d));

            // read as stuck consumer to create pending (never ack)
            stream.readGroup(group, stuckConsumer, StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofMillis(100)));

            // start our consumer with small claim idle and fast scan
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .claimIdleMs(10)
                    .claimBatchSize(10)
                    .pendingScanIntervalSec(1)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer consumer = factory.createConsumer("worker-1");
            CountDownLatch handled = new CountDownLatch(1);
            consumer.subscribe(topic, group, m -> {
                handled.countDown();
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();

            boolean ok = handled.await(5, TimeUnit.SECONDS);
            assertTrue(ok, "expected claimed pending to be handled");

            consumer.stop();
            consumer.close();
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
