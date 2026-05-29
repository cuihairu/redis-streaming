package io.github.cuihairu.redis.streaming.mq.dlq;

import org.redisson.api.StreamMessageId;

import java.util.List;

/**
 * DLQ operations/administration interface.
 */
public interface DeadLetterAdmin {
    List<String> listTopics();

    long size(String topic);

    List<DeadLetterEntry> list(String topic, int limit);

    boolean replay(String topic, StreamMessageId id);

    long replayAll(String topic, int maxCount);

    boolean delete(String topic, StreamMessageId id);

    long clear(String topic);
}
