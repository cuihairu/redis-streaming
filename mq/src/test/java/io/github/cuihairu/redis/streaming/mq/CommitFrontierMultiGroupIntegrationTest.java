package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class CommitFrontierMultiGroupIntegrationTest {

    @Test
    void frontierUpdatedForTwoGroups() throws Exception {
        String topic = "frontier2-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(100).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer p = factory.createProducer();
            MessageConsumer c1 = factory.createConsumer("c-f1");
            MessageConsumer c2 = factory.createConsumer("c-f2");
            CountDownLatch h1 = new CountDownLatch(1);
            CountDownLatch h2 = new CountDownLatch(1);
            c1.subscribe(topic, "g1", m -> { h1.countDown(); return MessageHandleResult.SUCCESS; });
            c2.subscribe(topic, "g2", m -> { h2.countDown(); return MessageHandleResult.SUCCESS; });
            c1.start(); c2.start();

            p.send(topic, "k", "v").get(3, TimeUnit.SECONDS);

            assertTrue(h1.await(3, TimeUnit.SECONDS));
            assertTrue(h2.await(3, TimeUnit.SECONDS));

            String frontierKey = StreamKeys.commitFrontier(topic, 0);
            // Frontier update is best-effort after ACK; wait briefly for both group entries to appear
            boolean both = waitUntil(() -> {
                Map<String,String> m = client.<String,String>getMap(frontierKey).readAllMap();
                return m != null && m.containsKey("g1") && m.containsKey("g2") && m.get("g1") != null && m.get("g2") != null;
            }, 3000);
            assertTrue(both, "frontier map should contain both g1 and g2");

            c1.stop(); c1.close(); c2.stop(); c2.close(); p.close();
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            Boolean ok = cond.call();
            if (ok != null && ok) return true;
            Thread.sleep(50);
        }
        return false;
    }
}
