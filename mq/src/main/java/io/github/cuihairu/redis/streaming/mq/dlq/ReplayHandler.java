package io.github.cuihairu.redis.streaming.mq.dlq;

import java.util.Map;

/**
 * Publish replayed messages back to MQ (or other targets) without coupling to MQ implementation.
 */
@FunctionalInterface
public interface ReplayHandler {
    boolean publish(String topic, int partitionId, Object payload, Map<String,String> headers, int maxRetries);
}
