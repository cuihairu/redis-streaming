package io.github.cuihairu.redis.streaming.benchmark;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end MQ benchmark: produces {@code messageCount} messages through
 * {@link MessageProducer} while a consumer group drains the topic, measuring wall-clock
 * throughput and per-message end-to-end latency (send timestamp embedded in the payload,
 * read at consume time).
 */
public final class MqThroughputBenchmark {

    public BenchmarkResult run(RedissonClient redis, String topic, int messageCount, int payloadBytes) throws Exception {
        MessageQueueFactory mq = new MessageQueueFactory(redis);
        MessageProducer producer = mq.createProducer();
        MessageConsumer consumer = mq.createConsumer(topic + "-consumer");

        AtomicInteger received = new AtomicInteger();
        long[] latencies = new long[messageCount];
        consumer.subscribe(topic, topic + "-group", message -> {
            int slot = received.getAndIncrement();
            if (slot < messageCount) {
                try {
                    String payload = String.valueOf(message.getPayload());
                    long sendMs = Long.parseLong(payload.substring(0, payload.indexOf(':')));
                    latencies[slot] = System.currentTimeMillis() - sendMs;
                } catch (Exception ignore) {
                    // malformed probe payload: still counted, latency recorded as 0
                }
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        // warmup (code paths, connections) — not measured
        int warmup = Math.min(100, Math.max(1, messageCount / 10));
        for (int i = 0; i < warmup; i++) {
            producer.send(topic, "warm", System.currentTimeMillis() + ":w" + i).get(10, TimeUnit.SECONDS);
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (received.get() < warmup && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        received.set(0);

        String suffix = payloadBytes > 0 ? ":" + "x".repeat(payloadBytes) : "";
        long start = System.nanoTime();
        for (int i = 0; i < messageCount; i++) {
            producer.send(topic, "k" + (i % 8), System.currentTimeMillis() + ":" + i + suffix)
                    .get(10, TimeUnit.SECONDS);
        }
        deadline = System.currentTimeMillis() + 120_000;
        while (received.get() < messageCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        try {
            consumer.stop();
            consumer.close();
        } finally {
            producer.close();
        }

        int done = Math.min(received.get(), messageCount);
        if (done < messageCount) {
            throw new IllegalStateException("consumer only processed " + done + "/" + messageCount + " messages");
        }
        return new BenchmarkResult("mq produce+consume", messageCount, elapsedMs,
                messageCount * 1000.0 / Math.max(elapsedMs, 1),
                Percentiles.p50(latencies), Percentiles.p95(latencies), Percentiles.p99(latencies));
    }
}
