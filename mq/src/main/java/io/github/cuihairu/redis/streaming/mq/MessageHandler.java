package io.github.cuihairu.redis.streaming.mq;

/**
 * Message handler interface for processing messages
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handle a received message
     *
     * @param message the received message
     * @return processing result
     */
    MessageHandleResult handle(Message message);
}