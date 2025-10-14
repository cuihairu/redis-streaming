package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.api.stream.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Streams based message consumer implementation with consumer group support
 */
@Slf4j
public class RedisMessageConsumer implements MessageConsumer {

    private final RedissonClient redissonClient;
    private final String consumerName;
    private final ScheduledExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, ConsumerContext> consumers = new ConcurrentHashMap<>();

    public RedisMessageConsumer(RedissonClient redissonClient, String consumerName) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.executorService = Executors.newScheduledThreadPool(4);
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        subscribe(topic, "default-group", handler);
    }

    @Override
    public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
        if (closed.get()) {
            throw new IllegalStateException("Consumer is closed");
        }

        ConsumerContext context = new ConsumerContext(topic, consumerGroup, handler);
        consumers.put(topic, context);

        // Create consumer group if it doesn't exist
        try {
            RStream<String, Object> stream = redissonClient.getStream(topic);
            stream.createGroup(StreamCreateGroupArgs.name(consumerGroup).makeStream());
            log.info("Created consumer group '{}' for topic '{}'", consumerGroup, topic);
        } catch (Exception e) {
            // Group might already exist, which is fine
            log.debug("Consumer group '{}' for topic '{}' might already exist", consumerGroup, topic);
        }

        log.info("Subscribed to topic '{}' with consumer group '{}' and consumer name '{}'",
                topic, consumerGroup, consumerName);
    }

    @Override
    public void unsubscribe(String topic) {
        ConsumerContext context = consumers.remove(topic);
        if (context != null) {
            log.info("Unsubscribed from topic '{}'", topic);
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Redis message consumer '{}'", consumerName);

            // Start consuming messages for each subscribed topic
            consumers.forEach((topic, context) -> {
                executorService.submit(() -> consumeMessages(context));
            });

            // Schedule pending message check
            executorService.scheduleWithFixedDelay(this::processPendingMessages,
                    30, 30, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Redis message consumer '{}'", consumerName);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            consumers.clear();
            log.info("Redis message consumer '{}' closed", consumerName);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    private void consumeMessages(ConsumerContext context) {
        String topic = context.topic;
        String consumerGroup = context.consumerGroup;
        MessageHandler handler = context.handler;

        RStream<String, Object> stream = redissonClient.getStream(topic);

        while (running.get() && !closed.get()) {
            try {
                // Read new messages from the stream
                Map<StreamMessageId, Map<String, Object>> messages = stream.readGroup(
                        consumerGroup,
                        consumerName,
                        StreamReadGroupArgs.neverDelivered().count(1).timeout(Duration.ofSeconds(1))
                );

                for (Map.Entry<StreamMessageId, Map<String, Object>> entry : messages.entrySet()) {
                    StreamMessageId messageId = entry.getKey();
                    Map<String, Object> data = entry.getValue();

                    Message message = createMessageFromData(topic, messageId.toString(), data);

                    try {
                        MessageHandleResult result = handler.handle(message);
                        handleResult(stream, consumerGroup, messageId, message, result);
                    } catch (Exception e) {
                        log.error("Error handling message {} from topic '{}'", messageId, topic, e);
                        handleFailedMessage(stream, consumerGroup, messageId, message);
                    }
                }

            } catch (Exception e) {
                if (running.get() && !closed.get()) {
                    log.error("Error consuming messages from topic '{}'", topic, e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void processPendingMessages() {
        if (!running.get() || closed.get()) {
            return;
        }

        consumers.forEach((topic, context) -> {
            try {
                RStream<String, Object> stream = redissonClient.getStream(topic);

                // Get pending messages for this consumer
                @SuppressWarnings("deprecation")
                List<PendingEntry> pendingEntries = stream.listPending(context.consumerGroup, consumerName, StreamMessageId.MIN, StreamMessageId.MAX, 10);

                for (PendingEntry pendingEntry : pendingEntries) {
                    StreamMessageId messageId = pendingEntry.getId();

                    // Check if message has been pending too long (over 5 minutes)
                    if (pendingEntry.getIdleTime() > 300000) {
                        try {
                            // Claim the message back
                            Map<StreamMessageId, Map<String, Object>> claimed = stream.claim(
                                    context.consumerGroup,
                                    consumerName,
                                    300000L,
                                    TimeUnit.MILLISECONDS,
                                    messageId
                            );

                            for (Map.Entry<StreamMessageId, Map<String, Object>> entry : claimed.entrySet()) {
                                StreamMessageId claimedId = entry.getKey();
                                Map<String, Object> data = entry.getValue();

                                Message message = createMessageFromData(topic, claimedId.toString(), data);

                                try {
                                    MessageHandleResult result = context.handler.handle(message);
                                    handleResult(stream, context.consumerGroup, claimedId, message, result);
                                } catch (Exception e) {
                                    log.error("Error reprocessing pending message {} from topic '{}'", claimedId, topic, e);
                                    handleFailedMessage(stream, context.consumerGroup, claimedId, message);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error claiming pending message {} from topic '{}'", messageId, topic, e);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error processing pending messages for topic '{}'", topic, e);
            }
        });
    }

    private void handleResult(RStream<String, Object> stream, String consumerGroup,
                             StreamMessageId messageId, Message message, MessageHandleResult result) {
        switch (result) {
            case SUCCESS:
                // Acknowledge the message
                stream.ack(consumerGroup, messageId);
                log.debug("Message {} acknowledged", messageId);
                break;

            case RETRY:
                if (message.hasExceededMaxRetries()) {
                    sendToDeadLetterQueue(message);
                    stream.ack(consumerGroup, messageId);
                } else {
                    // Increment retry count and leave message unacknowledged for retry
                    message.incrementRetryCount();
                    log.debug("Message {} will be retried, count: {}", messageId, message.getRetryCount());
                }
                break;

            case FAIL:
            case DEAD_LETTER:
                sendToDeadLetterQueue(message);
                stream.ack(consumerGroup, messageId);
                break;
        }
    }

    private void handleFailedMessage(RStream<String, Object> stream, String consumerGroup,
                                   StreamMessageId messageId, Message message) {
        if (message.hasExceededMaxRetries()) {
            sendToDeadLetterQueue(message);
            stream.ack(consumerGroup, messageId);
        } else {
            message.incrementRetryCount();
            log.debug("Failed message {} will be retried, count: {}", messageId, message.getRetryCount());
        }
    }

    private void sendToDeadLetterQueue(Message message) {
        try {
            String dlqTopic = message.getTopic() + ".dlq";
            RStream<String, Object> dlqStream = redissonClient.getStream(dlqTopic);

            Map<String, Object> dlqData = Map.of(
                    "originalTopic", message.getTopic(),
                    "payload", message.getPayload(),
                    "timestamp", message.getTimestamp().toString(),
                    "failedAt", Instant.now().toString(),
                    "retryCount", message.getRetryCount(),
                    "key", message.getKey() != null ? message.getKey() : "",
                    "headers", message.getHeaders() != null ? message.getHeaders() : Map.of()
            );

            StreamAddArgs<String, Object> addArgs = StreamAddArgs.entries(dlqData);
            dlqStream.add(addArgs);
            log.warn("Message sent to dead letter queue: {}", dlqTopic);
        } catch (Exception e) {
            log.error("Failed to send message to dead letter queue", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Message createMessageFromData(String topic, String messageId, Map<String, Object> data) {
        Message message = new Message();
        message.setId(messageId);
        message.setTopic(topic);
        message.setPayload(data.get("payload"));

        String timestampStr = (String) data.get("timestamp");
        if (timestampStr != null) {
            message.setTimestamp(Instant.parse(timestampStr));
        } else {
            message.setTimestamp(Instant.now());
        }

        Integer retryCount = (Integer) data.get("retryCount");
        if (retryCount != null) {
            message.setRetryCount(retryCount);
        }

        Integer maxRetries = (Integer) data.get("maxRetries");
        if (maxRetries != null) {
            message.setMaxRetries(maxRetries);
        }

        String key = (String) data.get("key");
        if (key != null) {
            message.setKey(key);
        }

        Map<String, String> headers = (Map<String, String>) data.get("headers");
        if (headers != null) {
            message.setHeaders(headers);
        }

        return message;
    }

    private static class ConsumerContext {
        final String topic;
        final String consumerGroup;
        final MessageHandler handler;

        ConsumerContext(String topic, String consumerGroup, MessageHandler handler) {
            this.topic = topic;
            this.consumerGroup = consumerGroup;
            this.handler = handler;
        }
    }
}