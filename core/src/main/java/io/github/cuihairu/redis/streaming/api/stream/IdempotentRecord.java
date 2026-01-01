package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;
import java.util.Objects;

/**
 * A small envelope carrying an idempotency key for sink-side idempotent writes.
 *
 * <p>Typical usage: map a source message into {@code new IdempotentRecord<>(stableId, payload)}
 * and let the sink deduplicate based on {@code id}.</p>
 */
public record IdempotentRecord<T>(String id, T value) implements Serializable {

    public IdempotentRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}

