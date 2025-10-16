package io.github.cuihairu.redis.streaming.starter;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.starter.metrics.RetentionFrontierMetricsBinder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetentionFrontierMetricsIntegrationTest {

    @Test
    void frontierAgeGaugeExistsAndNonNegative() throws Exception {
        String topic = "frontgage-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        MeterRegistry reg = new SimpleMeterRegistry();
        try {
            MqOptions opts = MqOptions.builder().defaultConsumerGroup("g1").build();
            MessageQueueFactory f = new MessageQueueFactory(client, opts);
            MessageProducer p = f.createProducer();
            MessageConsumer c = f.createConsumer("c-frontg");
            final int[] handled = {0};
            c.subscribe(topic, "g1", m -> { handled[0]++; return MessageHandleResult.SUCCESS; });
            c.start();
            for (int i = 0; i < 50; i++) p.send(topic, "k"+i, "v"+i).join();
            boolean ok = waitUntil(() -> handled[0] >= 50, 8000);
            assertTrue(ok);

            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client);
            new RetentionFrontierMetricsBinder(client, admin, opts).bindTo(reg);
            Double val = reg.find("redis_streaming_mq_frontier_age_ms").gauge().value();
            assertNotNull(val);
            assertTrue(val >= 0.0);
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

