package io.github.cuihairu.redis.streaming.mq;

/**
 * Result of message handling
 */
public enum MessageHandleResult {
    /**
     * Message processed successfully
     */
    SUCCESS,

    /**
     * Message processing failed, should retry
     */
    RETRY,

    /**
     * Message processing failed, should not retry
     */
    FAIL,

    /**
     * Message should be sent to dead letter queue
     */
    DEAD_LETTER
}