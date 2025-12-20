package io.github.cuihairu.redis.streaming.runtime.internal;

record KeyedRecord<K, T>(K key, T value) {
}

