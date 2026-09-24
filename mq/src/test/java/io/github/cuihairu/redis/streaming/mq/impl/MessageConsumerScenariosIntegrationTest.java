package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario coverage for {@link RedisMessageConsumer} branches not hit by the happy-path
 * suites: RETRY re-delivery, FAIL to DLQ, in-flight backpressure accounting,
 * pause/resume and lifecycle transitions.
 */
@Tag("integration")
class MessageConsumerScenariosIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static MqOptions opts() {
        return MqOptions.builder()
                .workerThreads(1).schedulerThreads(1)
                .consumerPollTimeoutMs(200)
                .retryBaseBackoffMs(200).retryMaxAttempts(3)
                .build();
    }

    @Test
    void retryThenSucceed() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "cons-retry-" + uid;
        try {
            MessageQueueFactory mq = new MessageQueueFactory(client, opts());
            MessageProducer producer = mq.createProducer();
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            MessageConsumer consumer = mq.createConsumer("c-" + uid);
            consumer.subscribe(topic, "g", message -> {
                if (attempts.incrementAndGet() == 1) {
                    return MessageHandleResult.RETRY;
                }
                done.countDown();
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();
            producer.send(topic, "k", "payload").get(5, TimeUnit.SECONDS);

            assertTrue(done.await(20, TimeUnit.SECONDS), "message should succeed after retry; attempts=" + attempts.get());
            assertTrue(attempts.get() >= 2);
            consumer.stop();
            consumer.close();
            producer.close();
        } finally {
            client.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            client.shutdown();
        }
    }

    @Test
    void failSendsToDeadLetterQueue() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "cons-dlq-" + uid;
        try {
            MessageQueueFactory mq = new MessageQueueFactory(client, opts());
            MessageProducer producer = mq.createProducer();
            CountDownLatch failed = new CountDownLatch(1);
            MessageConsumer consumer = mq.createConsumer("c-" + uid);
            consumer.subscribe(topic, "g", message -> {
                failed.countDown();
                return MessageHandleResult.DEAD_LETTER;
            });
            consumer.start();
            producer.send(topic, "k", "doomed").get(5, TimeUnit.SECONDS);
            assertTrue(failed.await(20, TimeUnit.SECONDS));

            io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService dlq =
                    new io.github.cuihairu.redis.streaming.mq.dlq.RedisDeadLetterService(client);
            long deadline = System.currentTimeMillis() + 10_000;
            while (dlq.size(topic) == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(1L, dlq.size(topic), "dead-lettered record should land in the DLQ");
            dlq.clear(topic);

            consumer.close();
            consumer.close(); // idempotent
            producer.close();
        } finally {
            client.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            client.getKeys().deleteByPattern("dlq*" + topic + "*");
            client.shutdown();
        }
    }

    @Test
    void backpressureTracksInFlightAndPauseHoldsConsumption() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "cons-bp-" + uid;
        try {
            MqOptions options = MqOptions.builder()
                    .workerThreads(1).schedulerThreads(1)
                    .consumerPollTimeoutMs(200)
                    .maxInFlight(1)
                    .build();
            MessageQueueFactory mq = new MessageQueueFactory(client, options);
            MessageProducer producer = mq.createProducer();
            CopyOnWriteArrayList<String> processed = new CopyOnWriteArrayList<>();
            io.github.cuihairu.redis.streaming.mq.MessageConsumer consumer = mq.createConsumer("c-" + uid);
            PausableMessageConsumer control = (PausableMessageConsumer) consumer;
            consumer.subscribe(topic, "g", message -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processed.add(String.valueOf(message.getPayload()));
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();

            for (int i = 0; i < 4; i++) {
                producer.send(topic, "k", "m" + i).get(5, TimeUnit.SECONDS);
            }
            long deadline = System.currentTimeMillis() + 20_000;
            while (processed.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(4, processed.size());
            // in-flight accounting decrements after the handler returns; give it a bounded
            // window to drain instead of asserting immediately (racy on slow runners)
            deadline = System.currentTimeMillis() + 5_000;
            while (control.inFlight() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0L, control.inFlight());

            // pause stops intake of further batches; at most one already-polled batch may finish
            control.pause();
            assertTrue(control.isPaused());
            producer.send(topic, "k", "after-pause").get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 3; i++) {
                producer.send(topic, "k", "held-" + i).get(5, TimeUnit.SECONDS);
            }
            Thread.sleep(1200);
            int duringPause = processed.size();
            assertTrue(duringPause < 8, "paused consumer must stop intake; processed=" + duringPause);
            control.resume();
            assertFalse(control.isPaused());
            deadline = System.currentTimeMillis() + 15_000;
            while (processed.size() < 8 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(8, processed.size());

            consumer.unsubscribe(topic);
            consumer.stop();
            consumer.stop(); // idempotent
            consumer.close();
            producer.close();
        } finally {
            client.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            client.shutdown();
        }
    }
}
