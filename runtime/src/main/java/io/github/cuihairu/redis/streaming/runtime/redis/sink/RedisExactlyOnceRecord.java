package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import java.io.Serializable;
import java.util.Objects;

/**
 * A Redis Streams message envelope carrying metadata required for atomic sink commit.
 *
 * <p>Intended to be produced by mapping a {@code mq.Message} and then consumed by a
 * Redis-only exactly-once sink implementation.</p>
 */
public record RedisExactlyOnceRecord<T>(
        String topic,
        String consumerGroup,
        int partitionId,
        String messageId,
        String idempotencyKey,
        T value
) implements Serializable {

    public RedisExactlyOnceRecord {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(value, "value");
        if (topic.isBlank()) throw new IllegalArgumentException("topic must not be blank");
        if (consumerGroup.isBlank()) throw new IllegalArgumentException("consumerGroup must not be blank");
        if (messageId.isBlank()) throw new IllegalArgumentException("messageId must not be blank");
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        if (partitionId < 0) throw new IllegalArgumentException("partitionId must be >= 0");
    }
}

