package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.window.assigners.SlidingWindow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Sliding windows (multi-window membership per record) and fire-per-record clamp. */
@Tag("integration")
class WindowedSlidingSessionIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void slidingWindowsAccumulateAcrossOverlappingRanges() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("slide-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:slide:" + UUID.randomUUID().toString().substring(0, 6))
                .windowMaxFiresPerRecord(16)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "slide-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            List<Integer> out = new CopyOnWriteArrayList<>();
            env.fromMqTopic(topic, "g")
                    .map(m -> Integer.parseInt((String) m.getPayload()))
                    .keyBy(v -> "k")
                    .window(SlidingWindow.<Integer>of(Duration.ofMillis(1500), Duration.ofMillis(1500)))
                    .sum(v -> v)
                    .addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                long phase = System.currentTimeMillis() % 1500;
                if (phase > 300) {
                    Thread.sleep(1500 - phase + 20);
                }
                producer.send(topic, "k", "3").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k", "4").get(5, TimeUnit.SECONDS);
                Thread.sleep(1700);
                producer.send(topic, "k", "999").get(5, TimeUnit.SECONDS); // watermark trigger
                long deadline = System.currentTimeMillis() + 12_000;
                while (out.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertTrue(out.contains(7), "sliding window sum should fire: " + out);
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.shutdown();
        }
    }
}
