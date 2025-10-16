package io.github.cuihairu.redis.streaming.reliability.dlq;

import java.util.Map;

/**
 * Publish replayed messages back to MQ (or other targets) without coupling to MQ implementation.
 */
@FunctionalInterface
public interface ReplayHandler {
    /**
     * Publish a message to the target topic/partition.
     *
     * @return true if published successfully
     */
    boolean publish(String topic, int partitionId, Object payload, Map<String,String> headers, int maxRetries);
}

