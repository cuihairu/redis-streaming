package io.github.cuihairu.redis.streaming.mq;

import java.util.concurrent.CompletableFuture;

/**
 * Message producer interface for sending messages to streams
 */
public interface MessageProducer {

    /**
     * Send a message to the specified topic
     *
     * @param message the message to send
     * @return future that completes with the message ID
     */
    CompletableFuture<String> send(Message message);

    /**
     * Send a message to the specified topic with key
     *
     * @param topic the topic name
     * @param key the message key for partitioning
     * @param payload the message payload
     * @return future that completes with the message ID
     */
    CompletableFuture<String> send(String topic, String key, Object payload);

    /**
     * Send a message to the specified topic
     *
     * @param topic the topic name
     * @param payload the message payload
     * @return future that completes with the message ID
     */
    CompletableFuture<String> send(String topic, Object payload);

    /**
     * Close the producer and release resources
     */
    void close();

    /**
     * Check if the producer is closed
     *
     * @return true if closed, false otherwise
     */
    boolean isClosed();
}