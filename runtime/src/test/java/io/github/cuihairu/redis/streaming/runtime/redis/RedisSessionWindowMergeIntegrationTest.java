package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.window.assigners.SessionWindow;
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

/**
 * Audit P0-1 regression: session windows must merge on the Redis runtime. Overlapping
 * session seeds of one key coalesce into the union window and fire once with every
 * merged element — the per-(key,start,end) member path used to give every element its
 * own window and its own fire. A seed arriving after the gap stays a separate session.
 *
 * <p>Window close is driven by the next same-key record's processing-time sweep (this
 * engine's sweep path when no watermark generator is wired); a same-key driver keeps
 * the sweep on the key's partition.</p>
 */
@Tag("integration")
class RedisSessionWindowMergeIntegrationTest {

    private static final long GAP_MS = 1_500;
    private static final long SETTLE_MS = 2_200;

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void overlappingSessionSeedsMergeIntoOneWindow() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("sess-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:sess:" + UUID.randomUUID().toString().substring(0, 6))
                .windowMaxFiresPerRecord(16)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        List<String> fired = new CopyOnWriteArrayList<>(); // "<elements>@<start>-<end>"
        long t1 = System.currentTimeMillis();
        long t3 = t1 + GAP_MS; // refined below once the post-gap send happens
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g")
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k")
                    .window(SessionWindow.<String>withGap(Duration.ofMillis(GAP_MS)))
                    .apply((k, w, values, out) -> {
                        int n = 0;
                        for (String ignored : values) {
                            n++;
                        }
                        fired.add(n + "@" + w.getStart() + "-" + w.getEnd());
                        out.collect(n);
                    })
                    .addSink(v -> { });
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k", "a").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k", "b").get(5, TimeUnit.SECONDS);
                long t2 = System.currentTimeMillis();
                assertTrue(t2 - t1 < GAP_MS, "test premise: both seeds inside one session gap");

                // past the merged union's close: the next same-key record's sweep fires it
                Thread.sleep(SETTLE_MS);
                t3 = System.currentTimeMillis();
                producer.send(topic, "k", "c").get(5, TimeUnit.SECONDS);
                // c is beyond the gap — a fresh session; past its close, one more record fires it
                Thread.sleep(SETTLE_MS);
                producer.send(topic, "k", "d").get(5, TimeUnit.SECONDS);
                producer.close();
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (fired.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(2, fired.size(), "one fire per session — not one fire per element: " + fired);
            String[] first = fired.get(0).split("@");
            assertEquals("2", first[0], "the merged session carries both elements: " + fired);
            String[] bounds = first[1].split("-");
            long start = Long.parseLong(bounds[0]);
            long end = Long.parseLong(bounds[1]);
            assertTrue(end - start >= GAP_MS, "the union window spans at least the session gap: " + fired);
            assertTrue(start >= t1 && end <= t3 + GAP_MS + 1_000,
                    "the union window covers the merged seeds' span: " + fired);
            String[] second = fired.get(1).split("@");
            assertEquals("1", second[0], "the post-gap seed fires its own single-element session: " + fired);
        } finally {
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.shutdown();
        }
    }

    @Test
    void mergedSessionCountsEveryElementOnce() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("sessc-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:sessc:" + UUID.randomUUID().toString().substring(0, 6))
                .windowMaxFiresPerRecord(16)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "sessc-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        List<Long> fired = new CopyOnWriteArrayList<>();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g")
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k")
                    .window(SessionWindow.<String>withGap(Duration.ofMillis(GAP_MS)))
                    .count()
                    .addSink(fired::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k", "a").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k", "b").get(5, TimeUnit.SECONDS);
                Thread.sleep(SETTLE_MS);
                producer.send(topic, "k", "c").get(5, TimeUnit.SECONDS);
                Thread.sleep(SETTLE_MS);
                producer.send(topic, "k", "d").get(5, TimeUnit.SECONDS);
                producer.close();
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (fired.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(List.of(2L, 1L), fired,
                    "the merged session counts both elements once; the post-gap session counts one: " + fired);
        } finally {
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.shutdown();
        }
    }
}
