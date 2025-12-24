package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryKeyedValueStateTest {

    @Test
    void requiresCurrentKeyForAccess() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        InMemoryKeyedValueState<String, Integer> state = new InMemoryKeyedValueState<>(
                store, new StateDescriptor<>("count", Integer.class, 0));

        assertThrows(IllegalStateException.class, state::value);
        assertThrows(IllegalStateException.class, () -> state.update(1));
        assertThrows(IllegalStateException.class, state::clear);
    }

    @Test
    void returnsDefaultValueAndIsIsolatedByKey() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        InMemoryKeyedValueState<String, Integer> state = new InMemoryKeyedValueState<>(
                store, new StateDescriptor<>("count", Integer.class, 7));

        store.setCurrentKey("k1");
        assertEquals(7, state.value());
        state.update(1);
        assertEquals(1, state.value());

        store.setCurrentKey("k2");
        assertEquals(7, state.value());
        state.update(2);
        assertEquals(2, state.value());

        store.setCurrentKey("k1");
        assertEquals(1, state.value());
    }

    @Test
    void updateNullClearsState() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        InMemoryKeyedValueState<String, Integer> state = new InMemoryKeyedValueState<>(
                store, new StateDescriptor<>("count", Integer.class, 0));

        store.setCurrentKey("k");
        state.update(10);
        assertEquals(10, state.value());

        state.update(null);
        assertEquals(0, state.value());
    }
}

