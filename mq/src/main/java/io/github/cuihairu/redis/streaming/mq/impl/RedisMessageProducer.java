package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Streams based message producer implementation
 */
@Slf4j
public class RedisMessageProducer implements MessageProducer {

    private final RedissonClient redissonClient;
    private final TopicRegistry topicRegistry;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RedisMessageProducer(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.topicRegistry = new TopicRegistry(redissonClient);
    }

    @Override
    public CompletableFuture<String> send(Message message) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Register topic in registry
                topicRegistry.registerTopic(message.getTopic());

                RStream<String, Object> stream = redissonClient.getStream(message.getTopic());

                Map<String, Object> data = new HashMap<>();
                data.put("payload", message.getPayload());
                data.put("timestamp", message.getTimestamp().toString());
                data.put("retryCount", message.getRetryCount());
                data.put("maxRetries", message.getMaxRetries());

                if (message.getKey() != null) {
                    data.put("key", message.getKey());
                }

                if (message.getHeaders() != null && !message.getHeaders().isEmpty()) {
                    data.put("headers", message.getHeaders());
                }

                // Add message to stream
                StreamMessageId messageId = stream.add(StreamAddArgs.entries(data));

                String id = messageId.toString();
                message.setId(id);

                log.debug("Message sent to topic '{}' with ID: {}", message.getTopic(), id);
                return id;

            } catch (Exception e) {
                log.error("Failed to send message to topic '{}'", message.getTopic(), e);
                throw new RuntimeException("Failed to send message", e);
            }
        });
    }

    @Override
    public CompletableFuture<String> send(String topic, String key, Object payload) {
        Message message = new Message(topic, key, payload);
        return send(message);
    }

    @Override
    public CompletableFuture<String> send(String topic, Object payload) {
        Message message = new Message(topic, payload);
        return send(message);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Redis message producer closed");
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }
}