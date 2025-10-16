package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * Dead Letter Queue service interface (topic-scoped).
 */
public interface DeadLetterService {
    StreamMessageId send(DeadLetterRecord record);

    Map<StreamMessageId, Map<String, Object>> range(String originalTopic, int limit);

    long size(String originalTopic);

    boolean delete(String originalTopic, StreamMessageId id);

    long clear(String originalTopic);

    boolean replay(String originalTopic, StreamMessageId id);
}

