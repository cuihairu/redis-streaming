package io.github.cuihairu.redis.streaming.mq;

/**
 * Message consumer interface for processing messages from streams
 */
public interface MessageConsumer {

    /**
     * Subscribe to a topic and start consuming messages
     *
     * @param topic the topic to subscribe to
     * @param handler the message handler
     */
    void subscribe(String topic, MessageHandler handler);

    /**
     * Subscribe to a topic with consumer group
     *
     * @param topic the topic to subscribe to
     * @param consumerGroup the consumer group name
     * @param handler the message handler
     */
    void subscribe(String topic, String consumerGroup, MessageHandler handler);

    /**
     * Unsubscribe from a topic
     *
     * @param topic the topic to unsubscribe from
     */
    void unsubscribe(String topic);

    /**
     * Start consuming messages
     */
    void start();

    /**
     * Stop consuming messages
     */
    void stop();

    /**
     * Close the consumer and release resources
     */
    void close();

    /**
     * Check if the consumer is running
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();

    /**
     * Check if the consumer is closed
     *
     * @return true if closed, false otherwise
     */
    boolean isClosed();
}