package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** MDC sampling enabled path + sink-deduplication replay suppression on the Redis engine. */
@Tag("integration")
class RuntimeMdcSinkDedupIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void mdcEnabledProcessesAndDeduplicatingSinkSuppressesReplays() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("mdcd-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:mdcd:" + UUID.randomUUID().toString().substring(0, 6))
                .mdcEnabled(true)
                .mdcSampleRate(1.0)
                .sinkDeduplicationEnabled(true)
                .sinkDeduplicationTtl(Duration.ofMinutes(2))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "mdcd-topic-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            Map<String, Integer> counts = new ConcurrentHashMap<>();
            env.fromMqTopic(topic, "g")
                    .map(m -> (String) m.getPayload())
                    .addSink(v -> counts.merge(v, 1, Integer::sum));
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k", "unique-1").get(5, TimeUnit.SECONDS);
                String dupId = producer.send(topic, "k", "unique-2").get(5, TimeUnit.SECONDS);
                long deadline = System.currentTimeMillis() + 10_000;
                while (counts.size() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertEquals(2, counts.size());

                // simulate a redelivery of unique-2 by re-publishing with the original id header
                Message replay = new Message(topic, "k", "unique-2", "test");
                java.util.Map<String, String> hdr = new java.util.HashMap<>();
                hdr.put(MqHeaders.ORIGINAL_MESSAGE_ID, dupId);
                replay.setHeaders(hdr);
                producer.send(replay).get(5, TimeUnit.SECONDS);
                Thread.sleep(1500);
                assertEquals(1, counts.get("unique-2"), "dedup should suppress the replayed delivery");
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.shutdown();
        }
    }
}
