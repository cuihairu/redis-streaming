package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
public class MaxLeasedPartitionsIntegrationTest {

    @Test
    void consumerShouldNotAcquireMoreLeasesThanConfigured() throws Exception {
        String topic = "lease-cap-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g";
        String consumerName = "c1-" + UUID.randomUUID().toString().substring(0, 6);

        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(4)
                    .workerThreads(1)
                    .schedulerThreads(1)
                    .rebalanceIntervalSec(1)
                    .renewIntervalSec(1)
                    .leaseTtlSeconds(10)
                    .maxLeasedPartitionsPerConsumer(1)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageConsumer consumer = factory.createConsumer(consumerName);

            // Keep handler fast; we only care about lease acquisition.
            consumer.subscribe(topic, group, m -> MessageHandleResult.SUCCESS);
            consumer.start();

            boolean ok = awaitLeasesStable(client, topic, group, consumerName, 4, 1);
            assertTrue(ok, "consumer should acquire <= 1 leases within timeout");

            consumer.stop();
            consumer.close();
        } finally {
            client.shutdown();
        }
    }

    private boolean awaitLeasesStable(RedissonClient client, String topic, String group, String consumerName,
                                      int partitionCount, int maxExpected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            int leased = 0;
            for (int i = 0; i < partitionCount; i++) {
                String key = StreamKeys.lease(topic, group, i);
                RBucket<String> b = client.getBucket(key, org.redisson.client.codec.StringCodec.INSTANCE);
                String owner = null;
                try {
                    owner = b.get();
                } catch (Exception ignore) {
                }
                if (consumerName.equals(owner)) {
                    leased++;
                }
            }
            if (leased > maxExpected) {
                return false;
            }
            // Also require at least 1 lease to ensure consumer actually started.
            if (leased >= 1) {
                return true;
            }
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
