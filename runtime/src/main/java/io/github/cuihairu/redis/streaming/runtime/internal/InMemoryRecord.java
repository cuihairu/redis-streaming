package io.github.cuihairu.redis.streaming.runtime.internal;

public record InMemoryRecord<T>(T value, long timestamp) {
}
