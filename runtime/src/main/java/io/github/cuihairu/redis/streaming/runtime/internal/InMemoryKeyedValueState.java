package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;

import java.util.Objects;

final class InMemoryKeyedValueState<K, T> implements ValueState<T> {
    private final InMemoryKeyedStateStore<K> store;
    private final StateDescriptor<T> descriptor;

    InMemoryKeyedValueState(InMemoryKeyedStateStore<K> store, StateDescriptor<T> descriptor) {
        this.store = Objects.requireNonNull(store, "store");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    @Override
    @SuppressWarnings("unchecked")
    public T value() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        Object v = store.get(descriptor.getName(), key);
        return v == null ? descriptor.getDefaultValue() : (T) v;
    }

    @Override
    public void update(T value) {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        if (value == null) {
            store.remove(descriptor.getName(), key);
            return;
        }
        store.put(descriptor.getName(), key, value);
    }

    @Override
    public void clear() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        store.remove(descriptor.getName(), key);
    }
}

