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

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class CommitFrontierUpdateIntegrationTest {

    @Test
    void ackAdvancesCommitFrontier() throws Exception {
        String topic = "frontier-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultConsumerGroup("g1").build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer p = factory.createProducer();
            MessageConsumer c = factory.createConsumer("c-frontier");
            final int[] handled = {0};
            c.subscribe(topic, "g1", m -> { handled[0]++; return MessageHandleResult.SUCCESS; });
            c.start();

            for (int i = 0; i < 10; i++) p.send(topic, "k"+i, "v"+i).join();

            boolean ok = waitUntil(() -> handled[0] >= 10, 8000);
            assertTrue(ok, "messages handled");

            String frontierKey = StreamKeys.commitFrontier(topic, 0);
            org.redisson.api.RMap<String,String> fmap = client.getMap(frontierKey);
            Map<String,String> fm = fmap.readAllMap();
            assertTrue(fm.containsKey("g1"));
            String id = fm.get("g1");
            assertNotNull(id);
            assertTrue(id.contains("-"), "should be stream id");

            c.stop(); c.close();
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
