package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.reliability.dlq.RedisDeadLetterService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * @deprecated Use reliability.dlq.DeadLetterService instead. This class now delegates to it.
 */
@Deprecated
@Slf4j
public class DeadLetterQueueManager {

    private final DeadLetterService delegate;

    public DeadLetterQueueManager(RedissonClient redissonClient) {
        this.delegate = new RedisDeadLetterService(redissonClient);
    }

    public Map<StreamMessageId, Map<String, Object>> getDeadLetterMessages(String originalTopic, int limit) {
        return delegate.range(originalTopic, limit);
    }

    public boolean replayMessage(String originalTopic, StreamMessageId messageId) {
        return delegate.replay(originalTopic, messageId);
    }

    public boolean deleteMessage(String originalTopic, StreamMessageId messageId) {
        return delegate.delete(originalTopic, messageId);
    }

    public long getDeadLetterQueueSize(String originalTopic) {
        return delegate.size(originalTopic);
    }

    public long clearDeadLetterQueue(String originalTopic) {
        return delegate.clear(originalTopic);
    }
}
