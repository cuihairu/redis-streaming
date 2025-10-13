package io.github.cuihairu.redis.streaming.reliability;

import java.io.Serializable;

/**
 * Strategy for handling failures in stream processing.
 */
public enum FailureStrategy implements Serializable {
    /**
     * Fail fast - stop processing immediately on error
     */
    FAIL_FAST,

    /**
     * Retry - attempt to retry the operation
     */
    RETRY,

    /**
     * Skip - skip the failed element and continue
     */
    SKIP,

    /**
     * Dead letter queue - send failed elements to a separate queue
     */
    DEAD_LETTER_QUEUE,

    /**
     * Ignore - log the error but continue processing
     */
    IGNORE
}
