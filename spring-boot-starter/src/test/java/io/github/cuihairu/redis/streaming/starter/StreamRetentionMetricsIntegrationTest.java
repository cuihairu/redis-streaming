package io.github.cuihairu.redis.streaming.starter;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.impl.RedisMessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.starter.maintenance.StreamRetentionHousekeeper;
import io.github.cuihairu.redis.streaming.starter.metrics.RetentionMicrometerCollector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
public class StreamRetentionMetricsIntegrationTest {

    @Test
    void metricsCountersIncreaseOnTrim() throws Exception {
        String topic = "trimmet-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        MeterRegistry reg = new SimpleMeterRegistry();
        RetentionMetrics.setCollector(new RetentionMicrometerCollector(reg));
        try {
            MqOptions opts = MqOptions.builder().retentionMaxLenPerPartition(50).trimIntervalSec(1).build();
            MessageQueueAdmin admin = new RedisMessageQueueAdmin(client);
            try (StreamRetentionHousekeeper hk = new StreamRetentionHousekeeper(client, admin, opts)) {
                String sk = StreamKeys.partitionStream(topic, 0);
                RStream<String,Object> s = client.getStream(sk);
                for (int i = 0; i < 500; i++) {
                    s.add(StreamAddArgs.entries(Map.of(
                            "payload", "x" + i,
                            "timestamp", Instant.now().toString(),
                            "retryCount", 0,
                            "maxRetries", 3,
                            "topic", topic,
                            "partitionId", 0
                    )));
                }
                // Force a run to emit metrics quickly
                hk.runOnce();
                boolean ok = waitUntil(() -> s.size() <= 500, 20000);
                assertTrue(ok);
                boolean metricOk = waitUntil(() -> {
                    java.util.Collection<Counter> cs = reg.find("redis_streaming_mq_trim_attempts_total").counters();
                    double sum = 0.0;
                    for (Counter c : cs) {
                        String t = c.getId().getTag("topic");
                        String stream = c.getId().getTag("stream");
                        if (topic.equals(t) && "main".equals(stream)) {
                            sum += c.count();
                        }
                    }
                    return sum > 0.0;
                }, 20000);
                assertTrue(metricOk);
            }
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
