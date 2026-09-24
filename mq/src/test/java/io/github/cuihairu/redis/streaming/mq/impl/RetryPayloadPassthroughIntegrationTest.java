package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: retry re-enqueue must pass String payloads through verbatim. Re-encoding
 * with objectToJson turned "hello" into "\"hello\"" on the second delivery and kept
 * escaping on every further retry.
 */
@Tag("integration")
class RetryPayloadPassthroughIntegrationTest {

    private RedissonClient client;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(cfg);
        topic = "retry-payload-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern("*" + topic + "*");
        client.shutdown();
    }

    private List<String> runRetryRoundTrip(long retryBaseBackoffMs) throws Exception {
        MqOptions options = MqOptions.builder()
                .workerThreads(1).schedulerThreads(1)
                .consumerPollTimeoutMs(200)
                .retryBaseBackoffMs(retryBaseBackoffMs)
                .retryMaxAttempts(3)
                .build();
        MessageQueueFactory mq = new MessageQueueFactory(client, options);
        MessageProducer producer = mq.createProducer();
        MessageConsumer consumer = mq.createConsumer("c-" + topic);

        List<String> payloads = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(topic, "g", message -> {
            payloads.add(String.valueOf(message.getPayload()));
            if (attempts.incrementAndGet() == 1) {
                return MessageHandleResult.RETRY;
            }
            done.countDown();
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();
        try {
            producer.send(topic, "k", "hello").get(10, TimeUnit.SECONDS);
            assertTrue(done.await(20, TimeUnit.SECONDS), "second delivery should happen");
            return payloads;
        } finally {
            consumer.close();
            producer.close();
        }
    }

    @Test
    void fastPathRetryKeepsPayloadVerbatim() throws Exception {
        // backoff <= 50 ms takes the direct re-enqueue path
        List<String> payloads = runRetryRoundTrip(10);
        assertEquals(List.of("hello", "hello"), payloads);
    }

    @Test
    void bucketPathRetryKeepsPayloadVerbatim() throws Exception {
        // backoff > 50 ms takes the retry-bucket + Lua mover path
        List<String> payloads = runRetryRoundTrip(300);
        assertEquals(List.of("hello", "hello"), payloads);
    }
}
