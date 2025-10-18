package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetentionFrontierMetricsBinderTest {

    @Test
    void gaugeReportsFrontierAge() throws Exception {
        String topic = "gauge-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).consumerPollTimeoutMs(50).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer p = factory.createProducer();
            // produce a few messages to initialize streams/metadata
            for (int i=0;i<3;i++) p.send(topic, "k", "v"+i).get();

            // simulate two groups with leases and commit frontier values
            String frontierKey = StreamKeys.commitFrontier(topic, 0);
            client.<String,String>getMap(frontierKey).put("g1", nowMinusMs(500));
            client.<String,String>getMap(frontierKey).put("g2", nowMinusMs(200));
            // mark leases alive so binder counts them as active groups
            setLease(client, topic, "g1", 0, Duration.ofSeconds(2));
            setLease(client, topic, "g2", 0, Duration.ofSeconds(2));

            MeterRegistry registry = new SimpleMeterRegistry();
            RetentionFrontierMetricsBinder binder = new RetentionFrontierMetricsBinder(client,
                    new io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin(client, opts), opts);
            binder.bindTo(registry);

            // read gauge value
            Double age = registry.get("redis_streaming_mq_frontier_age_ms").gauge().value();
            assertNotNull(age);
            assertTrue(age >= 0.0);
        } finally { client.shutdown(); }
    }

    private static void setLease(RedissonClient client, String topic, String group, int pid, Duration ttl) {
        String leaseKey = StreamKeys.lease(topic, group, pid);
        RBucket<String> b = client.getBucket(leaseKey);
        try { b.set("1", ttl); } catch (Exception ignore) {}
    }

    private static String nowMinusMs(long ms) {
        long now = System.currentTimeMillis();
        long ts = now - Math.max(0, ms);
        // Compose a redis stream id string with only ms part
        return Long.toString(ts);
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

